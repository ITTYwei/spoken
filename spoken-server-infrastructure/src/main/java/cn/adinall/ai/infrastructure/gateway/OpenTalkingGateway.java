package cn.adinall.ai.infrastructure.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import cn.adinall.ai.infrastructure.config.OpenTalkingProperties;
import cn.adinall.ai.types.enums.ResponseCodeEnum;
import cn.adinall.ai.types.exception.AppException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

/**
 * OpenTalking API 的网关：整个系统里唯一直接讲「OpenTalking 方言」的地方。
 *
 * 只用 JDK HttpClient，不引入 WebFlux；解析一律走 JsonNode（宽松），
 * 因为对端字段在不同版本里会漂移。
 *
 * 错误码约定（上层靠它做分支，所以这些字面量不能改）：
 * - 连不上 / 被中断 -> provider_unavailable（HTTP 503）
 * - HTTP >= 400 -> ot_http_{code}（如 ot_http_404，适配器据此重试「worker 未就绪」）
 * - 上行语音相关 -> ot_voice_*
 */
public class OpenTalkingGateway {

    private static final Logger log = LoggerFactory.getLogger(OpenTalkingGateway.class);

    private final OpenTalkingProperties props;
    private final HttpClient http;
    private final String base;

    /**
     * 构造网关：记住配置、把 baseUrl 去掉尾部斜杠，并建一个 JDK HttpClient
     * （连接超时取配置，强制 HTTP/1.1 —— 上游 SSE 在 1.1 下更稳）。
     */
    public OpenTalkingGateway(OpenTalkingProperties props) {
        this.props = props;
        this.base = stripTrailingSlash(props.getBaseUrl());
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(props.getConnectTimeoutMs()))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    // ---------------------------------------------------------------- REST

    /** GET /models，拉上游支持的驱动模型列表。 */
    public JsonNode models() {
        return get("/models");
    }

    /** GET /avatars，拉上游的数字人形象列表。 */
    public JsonNode avatars() {
        return get("/avatars");
    }

    /** GET /sessions/webrtc/ice-config，拉 WebRTC 的 ICE 服务器配置。 */
    public JsonNode iceConfig() {
        return get("/sessions/webrtc/ice-config");
    }

    /**
     * POST /sessions 创建会话。
     *
     * 只把「有值」的字段放进去：上游对 None 和 "" 的处理不一样，空串会盖掉它的默认配置。
     *
     * @param ttsVoice        会话默认音色。作用在 TTS 阶段，**脚本轨和生成轨都受它影响**。
     * @param llmSystemPrompt 发给 LLM 的系统提示词，只影响生成轨。
     *                        注意上游的优先级是 {@code persona_prompt or llm_system_prompt}——
     *                        形象一旦绑了 persona，这里传的会被静默忽略。
     */
    public JsonNode createSession(String avatarId, String model, String ttsVoice, String llmSystemPrompt) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("avatar_id", avatarId == null ? "" : avatarId);
        body.put("model", model == null ? "" : model);
        if (ttsVoice != null && !ttsVoice.isBlank()) {
            body.put("tts_voice", ttsVoice);
        }
        if (llmSystemPrompt != null && !llmSystemPrompt.isBlank()) {
            body.put("llm_system_prompt", llmSystemPrompt);
        }
        return post("/sessions", body);
    }

    /** GET /sessions/{id}，查会话当前状态。 */
    public JsonNode sessionState(String providerSessionId) {
        return get("/sessions/" + providerSessionId);
    }

    /** POST /sessions/{id}/webrtc/offer，把 offer SDP 交给上游换 answer。 */
    public JsonNode offer(String providerSessionId, String sdp) {
        return post("/sessions/" + providerSessionId + "/webrtc/offer", Map.of("sdp", sdp, "type", "offer"));
    }

    /** POST /sessions/{id}/speak；voice 为空时不带该字段，让上游用会话默认音色。 */
    public JsonNode speak(String providerSessionId, String text, String voice) {
        Map<String, Object> body = voice == null || voice.isBlank()
                ? Map.of("text", text)
                : Map.of("text", text, "voice", voice);
        return post("/sessions/" + providerSessionId + "/speak", body);
    }

    /**
     * POST /sessions/{id}/say —— 脚本轨：让数字人**原样念出**这段文本，不经过 LLM。
     *
     * 与 {@link #speak} 的区别：speak 的 text 是「用户说的话」，上游会用 LLM 生成回复；
     * say 的 text 就是数字人要说的话，直通 TTS。
     */
    public JsonNode say(String providerSessionId, String text, String voice, String commandId) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("text", text);
        if (voice != null && !voice.isBlank()) {
            body.put("voice", voice);
        }
        if (commandId != null && !commandId.isBlank()) {
            // 上游用它做重放去重：同 id 同内容返回 duplicate，同 id 不同内容直接报错。
            body.put("command_id", commandId);
        }
        return post("/sessions/" + providerSessionId + "/say", body);
    }

    /** POST /sessions/{id}/interrupt，中断当前这句。 */
    public JsonNode interrupt(String providerSessionId) {
        return post("/sessions/" + providerSessionId + "/interrupt", Map.of());
    }

    /** DELETE /sessions/{id}，释放会话及其 GPU 槽位。 */
    public void close(String providerSessionId) {
        delete("/sessions/" + providerSessionId);
    }

    // ------------------------------------------------------ 语音上行（服务端 STT）

    /**
     * 语音上行识别。
     *
     * 两种模式：
     * <ul>
     *   <li><b>pcm == null（主路径）</b> —— 不带文件，让上游用它**自己 WebRTC 上行缓冲**里的音频。
     *       那份音频经过了 WebRTC 的 AEC3，数字人外放的声音已经在麦克风里被减掉，
     *       所以不会进识别结果。客户端只需要发一个「我说完了」的信号。</li>
     *   <li><b>pcm 非空（兼容路径）</b> —— 把整段 PCM 包成 WAV 传上去。
     *       ⚠️ 这份音频**没有回声消除**：数字人说话时外放的声音会被一起录进去，
     *       表现为「用户没说话却识别出一句台词」。保留只是为了兜底和对比。</li>
     * </ul>
     *
     * /speak_audio 会在上游接受识别文本后继续触发 LLM/TTS；本地领域层负责发布 stt.final，
     * 因此不能再额外调用 /speak，否则数字人会把同一句话读两遍。
     */
    public String transcribeAudio(String providerSessionId, byte[] pcm, int sampleRate) {
        if (sampleRate != 16_000) {
            throw new AppException("unsupported_sample_rate",
                    "音频上行只接受 16kHz 单声道 PCM，收到 " + sampleRate + "Hz");
        }

        String boundary = "----SpokenAi" + java.util.UUID.randomUUID().toString().replace("-", "");
        byte[] body;
        if (pcm == null || pcm.length == 0) {
            body = multipartField(boundary, "source", "uplink");
        } else {
            if ((pcm.length & 1) != 0) {
                throw new AppException("invalid_audio", "PCM 音频长度必须是 16 位采样的整数倍");
            }
            body = multipartFile(boundary, pcmToWav(pcm, sampleRate));
        }
        HttpRequest request = request("/sessions/" + providerSessionId + "/speak_audio")
                .timeout(Duration.ofMillis(Math.max(props.getRequestTimeoutMs(), props.getVoiceTimeoutMs())))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        JsonNode response = send(request);
        String error = OpenTalkingJson.text(response, "error", "detail");
        if (error != null) {
            throw new AppException("ot_voice_rejected", error);
        }
        String text = OpenTalkingJson.text(response, "text");
        if (text == null) {
            throw new AppException("ot_voice_empty", "OpenTalking 未返回语音识别文本");
        }
        return text;
    }

    private static byte[] pcmToWav(byte[] pcm, int sampleRate) {
        ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        header.put(new byte[]{'R', 'I', 'F', 'F'});
        header.putInt(36 + pcm.length);
        header.put(new byte[]{'W', 'A', 'V', 'E', 'f', 'm', 't', ' '});
        header.putInt(16);
        header.putShort((short) 1);
        header.putShort((short) 1);
        header.putInt(sampleRate);
        header.putInt(sampleRate * 2);
        header.putShort((short) 2);
        header.putShort((short) 16);
        header.put(new byte[]{'d', 'a', 't', 'a'});
        header.putInt(pcm.length);
        byte[] wav = new byte[header.capacity() + pcm.length];
        System.arraycopy(header.array(), 0, wav, 0, header.capacity());
        System.arraycopy(pcm, 0, wav, header.capacity(), pcm.length);
        return wav;
    }

    /** 不带文件的 multipart 字段（用于告诉上游「用你的上行缓冲」）。 */
    private static byte[] multipartField(String boundary, String name, String value) {
        String text = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n"
                + value + "\r\n"
                + "--" + boundary + "--\r\n";
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] multipartFile(String boundary, byte[] wav) {
        try {
            ByteArrayOutputStream body = new ByteArrayOutputStream(wav.length + 256);
            body.write(("--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"file\"; filename=\"voice.wav\"\r\n"
                    + "Content-Type: audio/wav\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            body.write(wav);
            body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            return body.toByteArray();
        } catch (IOException e) {
            throw new AppException("ot_voice_failed", "构造语音上传请求失败", e);
        }
    }

    // ------------------------------------------------------------ SSE 事件流

    /**
     * 订阅 OpenTalking 的会话事件流，返回的是拼好的原始帧（{"event": ..., "data": ...}）。
     * 调用方（事件翻译器）负责把它翻成我方事件。取消订阅会自动关闭底层连接。
     */
    public Flux<String> eventStream(String providerSessionId) {
        HttpRequest request = HttpRequest.newBuilder(uri("/sessions/" + providerSessionId + "/events"))
                .header("Accept", "text/event-stream")
                .header("Cache-Control", "no-cache")
                .GET()
                .build();
        HttpResponse<Stream<String>> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofLines());
        } catch (IOException e) {
            throw new AppException(ResponseCodeEnum.PROVIDER_UNAVAILABLE, "OpenTalking 事件流不可达: " + base, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AppException(ResponseCodeEnum.PROVIDER_UNAVAILABLE, "OpenTalking 事件流被中断", e);
        }
        if (response.statusCode() >= 400) {
            try (Stream<String> body = response.body()) {
                String snippet = body.limit(20).reduce("", (a, b) -> a + b + "\n");
                throw new AppException("sse_http_" + response.statusCode(), "订阅事件流失败: " + snippet);
            }
        }
        Stream<String> lines = response.body();
        // OpenTalking 的 SSE 是「事件名在 event: 行、内容在 data: 行」。
        // 只读 data 行会把事件名丢掉，映射层就无从判断这是什么事件（字幕一直不出来
        // 就是这个原因），所以这里把两行合成 {"event": "...", "data": {...}} 再往下传。
        StringBuilder pendingEvent = new StringBuilder();
        // 注意 Flux.using 是 <T, D> 两个类型参数，写 Flux.<String>using 会编译不过
        return Flux.<String, Stream<String>>using(() -> lines, Flux::fromStream, Stream::close)
                // 显式给 handle 一个类型见证：不然 R 推不出来，会返回 Flux<Object>
                .<String>handle((line, sink) -> {
                    if (line.isEmpty()) {
                        pendingEvent.setLength(0); // 空行 = 一个 SSE 帧结束
                        return;
                    }
                    if (line.startsWith("event:")) {
                        pendingEvent.setLength(0);
                        pendingEvent.append(line.substring("event:".length()).trim());
                        return;
                    }
                    if (!line.startsWith("data:")) {
                        return; // 注释行、id:、retry: 之类一律忽略
                    }
                    String payload = line.substring("data:".length()).trim();
                    if (payload.isEmpty() || "[DONE]".equals(payload)) {
                        return;
                    }
                    String name = pendingEvent.length() == 0 ? "message" : pendingEvent.toString();
                    sink.next(merge(name, payload));
                })
                .doOnError(e -> log.warn("event stream error: {}", e.toString()))
                .doOnCancel(() -> log.debug("event stream cancelled: {}", providerSessionId));
    }

    /** 把 SSE 的 event 名与 data 内容合成一个 JSON 帧，交给翻译层。 */
    private static String merge(String name, String payload) {
        JsonNode parsed = OpenTalkingJson.read(payload);
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("event", name);
        // payload 不是合法 JSON 时原样带上，别把信息丢掉
        frame.put("data", parsed.isMissingNode() ? payload : parsed);
        try {
            return OpenTalkingJson.MAPPER.writeValueAsString(frame);
        } catch (Exception e) {
            return "{\"event\":\"" + name + "\"}";
        }
    }

    // ------------------------------------------------------------ internals

    /** 发一个 GET 请求并把响应解析成 JsonNode。 */
    private JsonNode get(String path) {
        return send(request(path).GET().build());
    }

    /** 发一个 POST：请求体先序列化成 JSON，带上 Content-Type: application/json，再解析响应。 */
    private JsonNode post(String path, Object body) {
        String json = writeJson(body);
        return send(request(path)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build());
    }

    /** 发一个 DELETE 请求，忽略响应体。 */
    private void delete(String path) {
        send(request(path).DELETE().build());
    }

    /** 构造请求的公共部分：拼出 URL、设置请求超时（配置的 requestTimeoutMs）、声明 Accept: application/json。 */
    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(uri(path))
                .timeout(Duration.ofMillis(props.getRequestTimeoutMs()))
                .header("Accept", "application/json");
    }

    /** 把配置的 baseUrl 和相对路径拼成完整 URI。 */
    private URI uri(String path) {
        return URI.create(base + path);
    }

    /**
     * 真正发请求并统一处理结果与错误。
     * 流程：记录起始时间 -> 发送 -> 抛 IOException 时按「上游不可达」抛 provider_unavailable ->
     * 状态码 >= 400 时从 error.message 或 detail/message 里挖出错误文案，打 warn 日志，抛 ot_http_{code} 异常 ->
     * 成功则打 debug 日志（含耗时）并返回解析后的 JsonNode。
     * 适配器就是靠这个 ot_http_404 识别「worker 还没就绪」并重试的。
     */
    private JsonNode send(HttpRequest request) {
        long t0 = System.currentTimeMillis();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new AppException(ResponseCodeEnum.PROVIDER_UNAVAILABLE,
                    "OpenTalking 请求失败: " + request.uri(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AppException(ResponseCodeEnum.PROVIDER_UNAVAILABLE,
                    "OpenTalking 请求被中断: " + request.uri(), e);
        }
        long cost = System.currentTimeMillis() - t0;
        JsonNode node = OpenTalkingJson.read(response.body());
        if (response.statusCode() >= 400) {
            String message = OpenTalkingJson.text(node.path("error"), "message");
            if (message == null) {
                message = OpenTalkingJson.text(node, "detail", "message");
            }
            log.warn("OpenTalking {} -> {} ({}ms) {}", request.uri(), response.statusCode(), cost,
                    message == null ? "" : message);
            throw new AppException("ot_http_" + response.statusCode(),
                    "OpenTalking 返回 " + response.statusCode() + (message == null ? "" : ": " + message));
        }
        log.debug("OpenTalking {} -> {} ({}ms)", request.uri(), response.statusCode(), cost);
        return node;
    }

    /** 把请求体序列化成 JSON 字符串；失败则抛 json_encode_failed（属于我方 bug，不该重试）。 */
    private static String writeJson(Object body) {
        try {
            return OpenTalkingJson.MAPPER.writeValueAsString(body);
        } catch (Exception e) {
            throw new AppException("json_encode_failed", "请求体序列化失败", e);
        }
    }

    /** 去掉 URL 结尾的斜杠；传空时回落到默认地址 http://127.0.0.1:8001。 */
    private static String stripTrailingSlash(String url) {
        if (url == null || url.isBlank()) {
            return "http://127.0.0.1:8001";
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}

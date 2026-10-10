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
 * OpenTalking API 的网关：整个系统里唯一直接讲「OpenTalking 方言」的地方
 * <p>
 * 功能：用 JDK HttpClient 封装上游的 REST 与 SSE 接口，并把 HTTP 层错误统一成 AppException 的错误码。
 * <pre>
 *   models() / avatars() / iceConfig()               GET /models、/avatars、/sessions/webrtc/ice-config
 *   createSession(avatarId, model, ttsVoice, prompt) POST /sessions（avatar_id、model 必发；tts_voice、prompt 非空才发）
 *   sessionState(providerSessionId)                  GET /sessions/{id}
 *   offer(providerSessionId, sdp)                    POST /sessions/{id}/webrtc/offer
 *   speak(id, text, voice)                           POST /sessions/{id}/speak（text 是用户的话，上游走 LLM）
 *   say(id, text, voice, commandId)                  POST /sessions/{id}/say（脚本轨直念，command_id 去重）
 *   interrupt(id) / close(id)                        POST /sessions/{id}/interrupt、DELETE /sessions/{id}
 *   transcribeAudio(id, pcm, sampleRate)             POST /sessions/{id}/speak_audio（multipart）
 *   eventStream(id)                                  GET /sessions/{id}/events（SSE -> Flux&lt;String&gt;）
 * </pre>
 * 体系架构（谁调它、方言在哪）：
 * <pre>
 *   OpenTalkingAvatarDriver（适配器，本类唯一的调用方）
 *        |
 *        +--> models / avatars / iceConfig / createSession / sessionState / offer
 *        |        返回 JsonNode（宽松解析，缺字段不抛异常）
 *        +--> speak / say / interrupt
 *        |        返回 JsonNode（适配器多数只关心「有没有抛错」）
 *        +--> close
 *        |        无返回值（void；内部委托 delete()，忽略响应体）
 *        +--> transcribeAudio     返回 String（识别文本）
 *        +--> eventStream         返回 Flux&lt;String&gt;（拼好的 {"event":...,"data":...} 帧）
 *        |
 *        v
 *   OpenTalkingGateway（本类）
 *        |
 *        +--> java.net.http.HttpClient     强制 HTTP/1.1；connectTimeoutMs 建连超时
 *        +--> OpenTalkingJson              解析与多字段名宽松取值
 *        +--> OpenTalkingProperties        baseUrl / requestTimeoutMs / voiceTimeoutMs
 *        |
 *        v
 *   OpenTalking 后端 baseUrl（默认 http://127.0.0.1:8001）
 * </pre>
 * 运行过程（一次 REST 调用，成功与失败两条路）：
 * <pre>
 *   gateway.speak("ot-7c1f2a", "hello", null)
 *        |
 *        | (1) post("/sessions/ot-7c1f2a/speak", {text:"hello"})
 *        |        writeJson(body) -> OpenTalkingJson.MAPPER.writeValueAsString
 *        |        （序列化失败抛 json_encode_failed，属我方 bug，不该重试）
 *        |
 *        | (2) request(path)：uri = base + path
 *        |        .timeout(requestTimeoutMs).header("Accept","application/json")
 *        |        .header("Content-Type","application/json")
 *        |        BodyPublishers.ofString(json)
 *        |
 *        | (3) send(request)
 *        |        |
 *        |        +--> IOException          -> 抛 AppException(provider_unavailable)
 *        |        +--> InterruptedException -> 恢复中断标记 + provider_unavailable
 *        |        +--> statusCode >= 400    -> 抛 AppException("ot_http_" + code, ...)
 *        |        |                            适配器据 ot_http_404 判断「worker 未就绪」并重试
 *        |        +--> 2xx                -> log.debug（含耗时）-> 返回 read(body) 的 JsonNode
 *        v
 *   JsonNode（解析不出时是 MissingNode，调用方用 OpenTalkingJson.text 宽松取）
 * </pre>
 * 案例：
 * <pre>
 *   输入: createSession(avatarId="anchor", model="flashhead",
 *                      ttsVoice="en-US-AriaNeural", llmSystemPrompt="You are Amy...")
 *   处理: body = {avatar_id:"anchor", model:"flashhead",
 *                 tts_voice:"en-US-AriaNeural", llm_system_prompt:"You are Amy..."}
 *         （ttsVoice 为空串时不带 tts_voice；形象绑了 persona 时上游会静默忽略 llm_system_prompt）
 *         POST http://127.0.0.1:8001/sessions
 *   输出: JsonNode{"session_id":"ot-7c1f2a","status":"queued"}，由适配器取出 session_id 并映射状态
 *   失败: 上游 404 -> AppException{code="ot_http_404", message="OpenTalking 返回 404: ..."}
 *   语音: transcribeAudio(id, null, 16000)  -> source=uplink 的 multipart（让上游用自己 WebRTC 上行缓冲）
 *         transcribeAudio(id, pcm, 8000)   -> AppException{code="unsupported_sample_rate"}
 *         上游回 {"error":"..."}           -> AppException{code="ot_voice_rejected"}
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>上游的 URI、字段名、错误码字面量全部收在本类：上层不做字符串判断，
 *       例外是适配器按 ot_http_404 / ot_http_409 分支，所以这些字面量不能改。</li>
 *   <li>只用 JDK HttpClient，不引入 WebFlux：强制 HTTP/1.1，上游 SSE 在 1.1 下更稳。</li>
 *   <li>解析一律走 JsonNode 且宽松（读不到给 null / 默认值）：对端字段在不同版本里会漂移。</li>
 *   <li>createSession 的 body 分两类：avatar_id 与 model 无条件发送、且 null 会被转成空串；
 *       tts_voice 与 llm_system_prompt 只在非空时才带上 —— 上游对「不传」与「传空串」的处理不一样，
 *       空串会盖掉它自己的默认配置，所以调用方必须先兜底（适配器已用配置默认值补上）。</li>
 *   <li>say 的 command_id 是给上游做重放去重的：同 id 同内容返回 duplicate，
 *       同 id 不同内容直接报错，所以这个参数不能随便生成。</li>
 *   <li>transcribeAudio 两种模式差别很关键：pcm 为 null 时才走上游 WebRTC 上行缓冲
 *       （经过 AEC3，数字人外放的声音已在麦克风里被减掉）；pcm 非空会包成 WAV 上传，
 *       这条路没有回声消除，只作兜底。</li>
 *   <li>eventStream 必须把 event: 与 data: 两行合成帧：只读 data 行会丢掉事件名，
 *       映射层无从判断这是什么事件（字幕一直不出来就是这个原因）。</li>
 * </ul>
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
     * body 分两类：avatar_id 与 model 无条件发送，null 会被转成空串（调用方应已兜底）；
     * tts_provider、tts_voice 与 llm_system_prompt 只在非空时才带上 —— 上游对「不传」和「传空串」
     * 的处理不一样，空串会盖掉它的默认配置。
     *
     * @param ttsProvider     TTS 引擎（edge / dashscope / ...）。**必须和 ttsVoice 配套**：
     *                        上游音色按引擎命名空间命名，只发音色不发引擎会解不出正确的音色。
     *                        空 = 不发，沿用上游全局默认。
     * @param ttsVoice        会话默认音色。作用在 TTS 阶段，**脚本轨和生成轨都受它影响**。
     * @param llmSystemPrompt 发给 LLM 的系统提示词，只影响生成轨。
     *                        注意上游的优先级是 {@code persona_prompt or llm_system_prompt}——
     *                        形象一旦绑了 persona，这里传的会被静默忽略。
     */
    public JsonNode createSession(String avatarId, String model, String ttsProvider, String ttsVoice,
                                  String llmSystemPrompt) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("avatar_id", avatarId == null ? "" : avatarId);
        body.put("model", model == null ? "" : model);
        if (ttsProvider != null && !ttsProvider.isBlank()) {
            body.put("tts_provider", ttsProvider);
        }
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

    /**
     * POST /sessions/{id}/speak —— 文本先过上游 LLM 生成回复再念。
     *
     * tts_provider 必须每次都带：上游在 speak/say 里是「请求级解析」的
     * （{@code _effective_tts_provider(requested)}），没带就回退到**上游全局默认引擎**，
     * **不会**继承建会话时传的 tts_provider —— 实测漏发会报
     * {@code invalid Edge TTS voice id: 'Cherry'}。
     */
    public JsonNode speak(String providerSessionId, String text, String voice, String ttsProvider) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("text", text);
        if (voice != null && !voice.isBlank()) {
            body.put("voice", voice);
        }
        if (ttsProvider != null && !ttsProvider.isBlank()) {
            body.put("tts_provider", ttsProvider);
        }
        return post("/sessions/" + providerSessionId + "/speak", body);
    }

    /**
     * POST /sessions/{id}/say —— 脚本轨：让数字人**原样念出**这段文本，不经过 LLM。
     *
     * 与 {@link #speak} 的区别：speak 的 text 是「用户说的话」，上游会用 LLM 生成回复；
     * say 的 text 就是数字人要说的话，直通 TTS。
     *
     * tts_provider 与 speak 同理**必须每次带**：上游不会从会话继承，漏发就回退全局默认引擎。
     */
    public JsonNode say(String providerSessionId, String text, String voice, String ttsProvider,
                        String commandId) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("text", text);
        if (voice != null && !voice.isBlank()) {
            body.put("voice", voice);
        }
        if (ttsProvider != null && !ttsProvider.isBlank()) {
            body.put("tts_provider", ttsProvider);
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

    /**
     * 上传一段 WAV 做识别，**不触发数字人播报**（POST /sessions/{id}/transcribe）。
     *
     * 与 {@link #transcribeAudio} 的关键区别：那条（不传 file 时）会在识别完成后继续跑
     * LLM→TTS→渲染，数字人会自己开口；本条只换回文本，说话与否由我方决定。
     * 「Java 接管 LLM」这条链路上必须先拿到文本（去评测、去跑 agent），所以走这条。
     *
     * @param wav 16kHz 单声道 16bit 的 WAV 字节（含 44 字节头）
     */
    public String transcribeUploaded(String providerSessionId, byte[] wav) {
        if (wav == null || wav.length == 0) {
            throw new AppException("invalid_audio", "音频为空");
        }
        String boundary = "----SpokenAi" + java.util.UUID.randomUUID().toString().replace("-", "");
        HttpRequest request = request("/sessions/" + providerSessionId + "/transcribe")
                .timeout(Duration.ofMillis(Math.max(props.getRequestTimeoutMs(), props.getVoiceTimeoutMs())))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(multipartFile(boundary, wav)))
                .build();
        JsonNode response = send(request);
        String error = OpenTalkingJson.text(response, "error", "detail");
        if (error != null) {
            throw new AppException("ot_transcribe_rejected", error);
        }
        String text = OpenTalkingJson.text(response, "text");
        if (text == null) {
            // 上游回 {"text": ""}（或压根没有 text 字段）= 这段音频里没有可识别的人声。
            // 这是**正常结果**，不是上游故障：AEC 把数字人的声音消掉之后，剩下的就是静音。
            // 判成 502 会让客户端把「你没说话」显示成「服务器挂了」——真机上已经出现过这种误报。
            throw new AppException(ResponseCodeEnum.NO_SPEECH, "没有识别出有效语音，请再说一遍");
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

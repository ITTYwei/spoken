package cn.adinall.ai.infrastructure.gateway;

import cn.adinall.ai.infrastructure.config.OpenPronounceProperties;
import cn.adinall.ai.types.enums.ResponseCodeEnum;
import cn.adinall.ai.types.exception.AppException;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * OpenPronounce API 的网关：整个系统里唯一直接讲「OpenPronounce 方言」的地方
 * <p>
 * 功能：用 JDK HttpClient 封装上游的 multipart / JSON 接口，并把 HTTP 层错误统一成 AppException 的错误码。
 * <pre>
 *   assess(audio, filename, expectedText, lang)   POST /pronunciation（multipart: file + expected_text + lang）
 *   speech2text(audio, filename, lang)            POST /speech2text（multipart: file + lang）
 *   phonemes(text, lang)                          POST /phonemes（form-urlencoded: text + lang）
 *   tts(text, lang)                               POST /tts（form-urlencoded: text + lang）-> wav 字节
 *   languages()                                   GET  /languages
 *   health()                                      GET  /health
 * </pre>
 * 体系架构（谁调它、方言在哪）：
 * <pre>
 *   OpenPronouncePronunciationAdapter（适配器，本类唯一的调用方）
 *        |
 *        +--> assess / speech2text / phonemes / languages
 *        |        返回 JsonNode（宽松解析，缺字段不抛异常）
 *        +--> tts
 *        |        返回 byte[]（上游回的是 audio/wav 二进制，不是 JSON）
 *        +--> health
 *        |        返回 boolean（探针语义：连不上/非 200 都算 false）
 *        |
 *        v
 *   OpenPronounceGateway（本类）
 *        |
 *        +--> java.net.http.HttpClient     强制 HTTP/1.1；connectTimeoutMs 建连超时
 *        +--> OpenTalkingJson              复用 gateway 包内那份宽松 ObjectMapper / read
 *        +--> OpenPronounceProperties      baseUrl / 各类超时 / maxAudioBytes
 *        |
 *        v
 *   OpenPronounce 服务 baseUrl（默认 http://127.0.0.1:8070）
 * </pre>
 * 运行过程（一次评测，成功与失败两条路）：
 * <pre>
 *   gateway.assess(wavBytes, "follow.wav", "Hello, how are you?", "en")
 *        |
 *        | (1) 本地先挡大小：audio.length > maxAudioBytes -> 抛 audio_too_large（413）
 *        |     （上游要收满整个 body 才判断，本地挡掉能省一次 15MB 上传）
 *        |
 *        | (2) 拼 multipart：file 部分带 filename 与 Content-Type；expected_text / lang 是文本字段
 *        |     文件名按后缀白名单归一（上游用 ffmpeg 按后缀解码，乱后缀会直接 500）
 *        |
 *        | (3) POST base + /pronunciation，超时取 assessTimeoutMs（不是 requestTimeoutMs）
 *        |
 *        | (4) send(request)
 *        |        |
 *        |        +--> IOException          -> 抛 AppException(provider_unavailable)（503）
 *        |        +--> InterruptedException -> 恢复中断标记 + provider_unavailable
 *        |        +--> statusCode >= 400    -> 抛 AppException("pronounce_http_" + code, ...)
 *        |        |                            上游是 FastAPI：参数错回 422、音频解不开回 400/500
 *        |        +--> 2xx                -> log.debug（含耗时）-> 返回 read(body) 的 JsonNode
 *        v
 *   JsonNode（score / differences / prosody ...）
 * </pre>
 * 案例：
 * <pre>
 *   输入: assess(37940 字节的 wav, "follow.wav", "Hello, how are you?", "en")
 *   处理: POST http://172.18.0.102:8070/pronunciation
 *   输出: JsonNode{"score":86.67,"distance":0,"acoustic_distance":0.0,
 *                 "differences":{...},"transcribe":"HELLO HOW ARE YOU",
 *                 "language":"en","prosody":{"f0":[...],"energy":[...]}}
 *   失败: 上游 422 -> AppException{code="pronounce_http_422", message="OpenPronounce 返回 422: ..."}
 *         服务不可达 -> AppException{code="provider_unavailable", message="OpenPronounce 请求失败: <uri>"}
 *   TTS: tts("Hello", "en") -> 37940 字节的 audio/wav（16kHz 单声道）
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>上游的 URI、multipart 字段名、状态码字面量全部收在本类：上层不做字符串判断。
 *       字段名必须与上游严格一致（file / expected_text / lang / text），
 *       写错的表现不是报错而是「上游把题面当成空」，那会静默给出 0 分 —— 这是最需要小心的一类错。</li>
 *   <li>只用 JDK HttpClient，不引入额外 HTTP 客户端：与 OpenTalkingGateway 保持一致，
 *       两个上游用同一套连接/超时/错误语义，排查时不用切换心智。</li>
 *   <li>解析一律走 JsonNode 且宽松（读不到给 null）：上游 0.3.0 的响应已经比它的 README
 *       多出好几个字段，字段漂移是常态。</li>
 *   <li>/tts 走单独的二进制分支：它的 Content-Type 是 audio/wav，
 *       用 ofString() 去接会把 wav 字节按字符集解坏，拿到的音频再也放不出声。</li>
 *   <li>文件名不是直接透传的：只取扩展名并与白名单比对（wav/mp3/m4a/ogg/webm/flac），
 *       不在白名单里就按 .wav 处理 —— 免得把用户提供的任意字符串当后缀送给 ffmpeg。</li>
 *   <li>health 不抛异常：它服务于探针，抛异常会让探针拿到 500 而分不清
 *       「服务不健康」和「探针本身坏了」。</li>
 * </ul>
 */
public class OpenPronounceGateway {

    private static final Logger log = LoggerFactory.getLogger(OpenPronounceGateway.class);

    /** 上游能解的容器格式白名单；不在里面的后缀一律按 .wav 处理。 */
    private static final java.util.Set<String> ALLOWED_SUFFIXES =
            java.util.Set.of(".wav", ".mp3", ".m4a", ".aac", ".ogg", ".opus", ".webm", ".flac", ".mp4");

    private final OpenPronounceProperties props;
    private final HttpClient http;
    private final String base;

    /**
     * 构造网关：记住配置、把 baseUrl 去掉尾部斜杠，并建一个 JDK HttpClient
     * （连接超时取配置，强制 HTTP/1.1）。
     */
    public OpenPronounceGateway(OpenPronounceProperties props) {
        this.props = props;
        this.base = stripTrailingSlash(props.getBaseUrl());
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(props.getConnectTimeoutMs()))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    // ---------------------------------------------------------------- 评测与语音

    /**
     * POST /pronunciation —— 录音打分（本网关最重要的一跳）。
     *
     * @param audio        音频字节，任意上游 ffmpeg 能解的格式
     * @param filename     原始文件名，只用它的扩展名
     * @param expectedText 期望文本（题面），必填
     * @param lang         语言码；调用方应已兜底
     */
    public JsonNode assess(byte[] audio, String filename, String expectedText, String lang) {
        requireAudio(audio);
        String boundary = newBoundary();
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("expected_text", expectedText);
        if (lang != null && !lang.isBlank()) {
            fields.put("lang", lang);
        }
        // 超时取 assessTimeoutMs：这一跳要跑两个 Wav2Vec2 模型 + DTW，
        // 和「查一下支持哪些语言」不是一个量级。
        HttpRequest request = request("/pronunciation", props.getAssessTimeoutMs())
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(
                        multipart(boundary, fields, audio, filename)))
                .build();
        return sendJson(request);
    }

    /** POST /speech2text —— 纯识别，不做评分。 */
    public JsonNode speech2text(byte[] audio, String filename, String lang) {
        requireAudio(audio);
        String boundary = newBoundary();
        Map<String, String> fields = new LinkedHashMap<>();
        if (lang != null && !lang.isBlank()) {
            fields.put("lang", lang);
        }
        HttpRequest request = request("/speech2text", props.getRequestTimeoutMs())
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(
                        multipart(boundary, fields, audio, filename)))
                .build();
        return sendJson(request);
    }

    /** POST /phonemes —— 文本音素化（逐词 IPA）。 */
    public JsonNode phonemes(String text, String lang) {
        return postForm("/phonemes", formFields(text, lang));
    }

    /**
     * POST /tts —— 合成参考发音。
     *
     * 上游回的是 audio/wav 二进制（16kHz 单声道），不是 JSON，所以走独立分支。
     * 本机部署用的是 piper（全离线）；若上游配成 gTTS，首次合成某句需要外网。
     */
    public byte[] tts(String text, String lang) {
        HttpRequest request = request("/tts", props.getRequestTimeoutMs())
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "audio/wav")
                .POST(HttpRequest.BodyPublishers.ofString(formEncode(formFields(text, lang))))
                .build();
        return sendBytes(request);
    }

    // ---------------------------------------------------------------- 能力查询

    /** GET /languages —— 支持的语言与默认语言。 */
    public JsonNode languages() {
        return getJson("/languages");
    }

    /**
     * GET /health —— 存活探测。
     *
     * 不抛异常：连不上、超时、非 200 都返回 false。探针要的是「能不能用」这一个布尔，
     * 而不是一条异常堆栈。
     */
    public boolean health() {
        try {
            HttpResponse<Void> response = http.send(
                    HttpRequest.newBuilder(uri("/health"))
                            .timeout(Duration.ofMillis(props.getConnectTimeoutMs() * 2L))
                            .header("Accept", "application/json")
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.discarding());
            return response.statusCode() == 200;
        } catch (IOException e) {
            log.debug("OpenPronounce 健康检查失败: {}", e.toString());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    // ------------------------------------------------------------ internals

    /** 发一个 GET 请求并把响应解析成 JsonNode。 */
    private JsonNode getJson(String path) {
        return sendJson(request(path, props.getRequestTimeoutMs()).GET().build());
    }

    /** 发一个 POST 表单请求（application/x-www-form-urlencoded）并解析响应。 */
    private JsonNode postForm(String path, Map<String, String> fields) {
        return sendJson(request(path, props.getRequestTimeoutMs())
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(formEncode(fields)))
                .build());
    }

    /** 构造请求的公共部分：拼 URL、设置超时、声明 Accept: application/json。 */
    private HttpRequest.Builder request(String path, int timeoutMs) {
        return HttpRequest.newBuilder(uri(path))
                .timeout(Duration.ofMillis(timeoutMs))
                .header("Accept", "application/json");
    }

    /** 将相对接口路径解析为提供方请求地址。 */
    private URI uri(String path) {
        return URI.create(base + path);
    }

    /**
     * 真正发请求并解析 JSON，统一处理结果与错误。
     * IOException -> provider_unavailable（503）；状态码 >= 400 -> pronounce_http_{code}。
     */
    private JsonNode sendJson(HttpRequest request) {
        long t0 = System.currentTimeMillis();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new AppException(ResponseCodeEnum.PROVIDER_UNAVAILABLE,
                    "OpenPronounce 请求失败: " + request.uri(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AppException(ResponseCodeEnum.PROVIDER_UNAVAILABLE,
                    "OpenPronounce 请求被中断: " + request.uri(), e);
        }
        long cost = System.currentTimeMillis() - t0;
        if (response.statusCode() >= 400) {
            throw httpError(request, response.statusCode(), response.body(), cost);
        }
        log.debug("OpenPronounce {} -> {} ({}ms)", request.uri(), response.statusCode(), cost);
        return OpenTalkingJson.read(response.body());
    }

    /** 发请求并要回原始字节（/tts 的 wav）。 */
    private byte[] sendBytes(HttpRequest request) {
        long t0 = System.currentTimeMillis();
        HttpResponse<byte[]> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw new AppException(ResponseCodeEnum.PROVIDER_UNAVAILABLE,
                    "OpenPronounce 请求失败: " + request.uri(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AppException(ResponseCodeEnum.PROVIDER_UNAVAILABLE,
                    "OpenPronounce 请求被中断: " + request.uri(), e);
        }
        long cost = System.currentTimeMillis() - t0;
        if (response.statusCode() >= 400) {
            // 错误体是 JSON 文本，但这里是二进制通道，按 UTF-8 读一小段当文案足够定位。
            String body = new String(response.body(), StandardCharsets.UTF_8);
            throw httpError(request, response.statusCode(), body, cost);
        }
        log.debug("OpenPronounce {} -> {} ({}ms, {}B)",
                request.uri(), response.statusCode(), cost, response.body().length);
        return response.body();
    }

    /**
     * 把上游的失败响应翻成一个带错误码的 AppException。
     * 流程：先从 FastAPI 的 {"detail": ...} 或 {"error": ...} 里挖文案 -> 打 warn 日志 -> 抛 pronounce_http_{code}。
     */
    private static AppException httpError(HttpRequest request, int statusCode, String body, long cost) {
        String message = extractError(body);
        log.warn("OpenPronounce {} -> {} ({}ms) {}", request.uri(), statusCode, cost,
                message == null ? "" : message);
        return new AppException("pronounce_http_" + statusCode,
                "OpenPronounce 返回 " + statusCode + (message == null ? "" : ": " + message));
    }

    /** 从上游错误体里挖一句可读文案；挖不到就给截断后的原文（最多 200 字符）。 */
    private static String extractError(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        JsonNode node = OpenTalkingJson.read(body);
        if (!node.isMissingNode()) {
            String detail = OpenTalkingJson.text(node, "detail", "error", "message");
            if (detail != null) {
                return detail;
            }
            // FastAPI 的 422 是 {"detail":[{"loc":[...],"msg":"...","type":"..."}]}，
            // text() 取不到（不是值节点），退回原文截断更有助于定位。
        }
        String trimmed = body.strip();
        return trimmed.length() <= 200 ? trimmed : trimmed.substring(0, 200) + "...";
    }

    /** 本地先挡大小与空音频，省掉一次注定失败的上传。 */
    private void requireAudio(byte[] audio) {
        if (audio == null || audio.length == 0) {
            throw new AppException(ResponseCodeEnum.INVALID_REQUEST, "音频为空");
        }
        if (audio.length > props.getMaxAudioBytes()) {
            throw new AppException(ResponseCodeEnum.AUDIO_TOO_LARGE,
                    "音频 " + audio.length + " 字节，超过上限 " + props.getMaxAudioBytes() + " 字节");
        }
    }

    /**
     * 拼一个 multipart/form-data 请求体：文本字段在前、文件字段在最后。
     *
     * 顺序不影响 FastAPI 的解析，但把文件放最后可以让前面几个小字段先被读出来，
     * 排查抓包时更容易看懂。
     */
    private static byte[] multipart(String boundary, Map<String, String> fields,
                                    byte[] audio, String filename) {
        try {
            ByteArrayOutputStream body = new ByteArrayOutputStream(
                    audio.length + 512 + fields.size() * 128);
            for (Map.Entry<String, String> field : fields.entrySet()) {
                if (field.getValue() == null) {
                    continue;
                }
                body.write(("--" + boundary + "\r\n"
                        + "Content-Disposition: form-data; name=\"" + field.getKey() + "\"\r\n\r\n")
                        .getBytes(StandardCharsets.UTF_8));
                body.write(field.getValue().getBytes(StandardCharsets.UTF_8));
                body.write("\r\n".getBytes(StandardCharsets.UTF_8));
            }
            body.write(("--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"file\"; filename=\"" + safeFilename(filename) + "\"\r\n"
                    + "Content-Type: " + contentTypeOf(filename) + "\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            body.write(audio);
            body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            return body.toByteArray();
        } catch (IOException e) {
            throw new AppException("pronounce_request_failed", "构造 OpenPronounce 上传请求失败", e);
        }
    }

    /**
     * 归一文件名：只保留扩展名，并挡掉不在白名单里的后缀。
     * 上游用 ffmpeg 按后缀解码，乱后缀会直接 500，所以这里不给「让上游自己猜」的机会。
     */
    private static String safeFilename(String filename) {
        String suffix = "";
        if (filename != null && !filename.isBlank()) {
            int dot = filename.lastIndexOf('.');
            if (dot >= 0 && dot < filename.length() - 1) {
                suffix = filename.substring(dot).toLowerCase(java.util.Locale.ROOT);
            }
        }
        if (!ALLOWED_SUFFIXES.contains(suffix)) {
            suffix = ".wav";
        }
        return "audio" + suffix;
    }

    /** 按后缀给 Content-Type；认不出来就当 wav。 */
    private static String contentTypeOf(String filename) {
        String name = safeFilename(filename);
        if (name.endsWith(".mp3")) {
            return "audio/mpeg";
        }
        if (name.endsWith(".m4a") || name.endsWith(".mp4") || name.endsWith(".aac")) {
            return "audio/mp4";
        }
        if (name.endsWith(".ogg") || name.endsWith(".opus")) {
            return "audio/ogg";
        }
        if (name.endsWith(".webm")) {
            return "audio/webm";
        }
        if (name.endsWith(".flac")) {
            return "audio/flac";
        }
        return "audio/wav";
    }

    /**
     * 拼 /phonemes 与 /tts 的表单字段。
     *
     * 这两个接口在上游是 FastAPI 的 {@code Form(...)}，OpenAPI 里写的是
     * application/x-www-form-urlencoded —— 用 JSON body 打过去会得到 422，
     * 而不是「安静地当成没传」，所以这里必须走表单编码。
     */
    private static Map<String, String> formFields(String text, String lang) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("text", text);
        if (lang != null && !lang.isBlank()) {
            fields.put("lang", lang);
        }
        return fields;
    }

    /** 把表单字段编码成 application/x-www-form-urlencoded 的请求体。 */
    private static String formEncode(Map<String, String> fields) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> field : fields.entrySet()) {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(URLEncoder.encode(field.getKey(), StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(field.getValue() == null ? "" : field.getValue(),
                            StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    /** 生成本次多部分表单请求使用的边界标识。 */
    private static String newBoundary() {
        return "----SpokenAiPronounce" + java.util.UUID.randomUUID().toString().replace("-", "");
    }

    /** 去掉 URL 结尾的斜杠；传空时回落到默认地址。 */
    private static String stripTrailingSlash(String url) {
        if (url == null || url.isBlank()) {
            return "http://127.0.0.1:8070";
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}

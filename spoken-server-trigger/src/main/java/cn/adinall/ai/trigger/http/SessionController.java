package cn.adinall.ai.trigger.http;

import cn.adinall.ai.api.dto.CreateConversationSessionRequestDTO;
import cn.adinall.ai.api.dto.IceServerResponseDTO;
import cn.adinall.ai.api.dto.OpeningResponseDTO;
import cn.adinall.ai.api.dto.SayRequestDTO;
import cn.adinall.ai.api.dto.SdpAnswerResponseDTO;
import cn.adinall.ai.api.dto.SdpOfferRequestDTO;
import cn.adinall.ai.api.dto.ServerEventDTO;
import cn.adinall.ai.api.dto.SessionResponseDTO;
import cn.adinall.ai.api.dto.SpeakRequestDTO;
import cn.adinall.ai.api.dto.TurnResponseDTO;
import cn.adinall.ai.cases.conversation.IConversationCase;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

/**
 * 会话 HTTP 入口（数字人对话链路的全部接口，路由前缀 /api/v1/sessions）
 * <p>
 * 功能：App 只认这一组接口；换数字人后端不影响它们。
 * Controller 里没有一行业务逻辑，也不该有：它只做「HTTP 形状 &lt;-&gt; 用例调用」的翻译，
 * 这样同一套用例将来可以被别的入口（定时任务、消息、gRPC）复用。
 * <pre>
 *   POST   /api/v1/sessions                      建会话（body 见 CreateConversationSessionRequestDTO）
 *   GET    /api/v1/sessions/{sessionId}          查会话状态（App 轮询 QUEUED -> READY）
 *   GET    /api/v1/sessions/ice-config           WebRTC 的 ICE 服务器配置
 *   POST   /api/v1/sessions/{sessionId}/offer    SDP 交换：offer 换 answer
 *   GET    /api/v1/sessions/{sessionId}/events   SSE 事件流（字幕 / 状态 / 心跳 / 错误）
 *   POST   /api/v1/sessions/{sessionId}/speak    文本驱动（当成用户说的话，过 LLM）
 *   POST   /api/v1/sessions/{sessionId}/say      脚本轨直念（不经过 LLM）
 *   POST   /api/v1/sessions/{sessionId}/opening  说本场景开场白（幂等）
 *   POST   /api/v1/sessions/{sessionId}/voice    语音上行（application/octet-stream）
 *   POST   /api/v1/sessions/{sessionId}/interrupt 打断当前这句
 *   DELETE /api/v1/sessions/{sessionId}          关会话并释放上游资源
 *   GET    /api/v1/sessions/{sessionId}/turns    本场历史回合
 * </pre>
 * 运行过程（建会话 -> 拿 ICE -> 换 SDP -> 听事件）：
 * <pre>
 *   App
 *        |
 *        | (1) POST /api/v1/sessions  {"deviceId":"dev-001","scenarioId":"free"}
 *        |        v
 *        |     conversationCase.create(request) --> SessionResponseDTO{status="READY"|"QUEUED"}
 *        |
 *        | (2) GET /api/v1/sessions/ice-config
 *        |        v
 *        |     Map.of("iceServers", conversationCase.iceServers())
 *        |
 *        | (3) POST /api/v1/sessions/{id}/offer   body={"sdp":"v=0...","type":"offer"}
 *        |        v
 *        |     new SdpAnswerResponseDTO(conversationCase.offer(id, sdp), "answer")
 *        |
 *        | (4) GET /api/v1/sessions/{id}/events   (Accept: text/event-stream)
 *        |        v
 *        |     Flux&lt;ServerEventDTO&gt;：session.ready / stt.final / assistant.delta / heartbeat ...
 *        |
 *        | (5) POST /api/v1/sessions/{id}/opening  --> OpeningResponseDTO
 *        v
 *   JSON / SSE 直接作为响应体返回（本类不包 Response 外壳）
 * </pre>
 * 案例：
 * <pre>
 *   输入: POST /api/v1/sessions {"deviceId":"dev-001","scenarioId":"free"}
 *   处理: 关旧会话 -> 建新会话 -> 等就绪（最多 15 秒）-> 返回对外形状
 *   输出: 200 {"sessionId":"s-001","status":"READY","voice":"en-US-AriaNeural",
 *          "startedAt":1767225600000}
 *         注意：请求里没给 avatarId / model，响应里也不会有这两个键 ——
 *         默认值只在下游调上游时兜底，对外形状是 @JsonInclude(NON_NULL) 且不回填
 *         POST /{id}/speak 或 /say: 200 {"status":"accepted"}
 *         DELETE /{id}:            200 {"status":"closed"}
 *         POST /{id}/voice:        200 {"text":"Hello"}（返回识别文本）
 *         会话不存在: 404 {"code":"session_not_found","message":"会话不存在或已结束: s-001","ts":...}
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>不写 try/catch：异常直接冒到 GlobalExceptionHandler，由它按 code 映射 HTTP 状态；
 *       这样「什么错对应什么状态码」只有一处，Controller 只管翻译形状。</li>
 *   <li>/voice 用 application/octet-stream 且 body 可空：空 body 是正常用法（表示「我说完了」，
 *       取服务端那份过了 AEC3 的上行缓冲），所以 @RequestBody(required = false)。</li>
 *   <li>/ice-config 外面包了一层 {"iceServers": [...]}：这是历史契约形状，App 按它解析，
 *       不要「顺手」改成裸数组。</li>
 *   <li>speak / say / interrupt / close 统一返回 {"status":"..."} 或 {"text":"..."}：
 *       下发是异步的，接口只确认受理，真正的进度从 /events 读。</li>
 *   <li>依赖只有一个 IConversationCase，且用构造器注入：Controller 薄到可以脱离 Spring 直接测。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/sessions")
public class SessionController {

    private final IConversationCase conversationCase;

    public SessionController(IConversationCase conversationCase) {
        this.conversationCase = conversationCase;
    }

    /** 建会话：请求体里带 deviceId / 形象 / 模型 / 音色，未指定的由后端配置兜底。 */
    @PostMapping
    public SessionResponseDTO create(@Valid @RequestBody CreateConversationSessionRequestDTO request) {
        return conversationCase.create(request);
    }

    /** 查会话状态。App 轮询这个接口就能看到 QUEUED 变 READY。 */
    @GetMapping("/{sessionId}")
    public SessionResponseDTO get(@PathVariable String sessionId) {
        return conversationCase.get(sessionId);
    }

    /**
     * WebRTC 的 ICE 服务器配置。
     * 包一层 {"iceServers": [...]} 是历史契约形状，App 按它解析，不要改成裸数组。
     */
    @GetMapping("/ice-config")
    public Map<String, List<IceServerResponseDTO>> iceConfig() {
        return Map.of("iceServers", conversationCase.iceServers());
    }

    /** WebRTC SDP 交换：offer 换 answer（non-trickle）。 */
    @PostMapping("/{sessionId}/offer")
    public SdpAnswerResponseDTO offer(@PathVariable String sessionId,
                                      @Valid @RequestBody SdpOfferRequestDTO request) {
        String answer = conversationCase.offer(sessionId, request.sdp());
        return new SdpAnswerResponseDTO(answer, "answer");
    }

    /** SSE 事件流：字幕、状态、错误都从这里出来。 */
    @GetMapping(value = "/{sessionId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerEventDTO> events(@PathVariable String sessionId) {
        return conversationCase.events(sessionId);
    }

    /** 文本驱动数字人说话（调试、无网时兜底）。 */
    @PostMapping("/{sessionId}/speak")
    public Map<String, Object> speak(@PathVariable String sessionId, @Valid @RequestBody SpeakRequestDTO request) {
        conversationCase.speak(sessionId, request);
        return Map.of("status", "accepted");
    }

    /**
     * 脚本轨：让数字人**原样念出**这段文字，不经过 LLM。
     *
     * 与 /speak 的分工：/speak 的 text 是「用户说的话」，会先让 LLM 生成回复再念；
     * /say 的 text 就是数字人要说的话，直通 TTS。
     * 开场白、题目、讲解、判分反馈这类**内容已定**的话走这里：
     * 实测首块音频约 1.0 秒（同样文本走 /speak 约 6.9 秒），且措辞完全可控。
     */
    @PostMapping("/{sessionId}/say")
    public Map<String, Object> say(@PathVariable String sessionId, @Valid @RequestBody SayRequestDTO request) {
        conversationCase.say(sessionId, request);
        return Map.of("status", "accepted");
    }

    /**
     * 开场：让数字人主动说出**本场景**的开场白（脚本轨，不经过 LLM）。
     *
     * 建会话成功后调用一次。**幂等**：重复调用不会让数字人重说一遍，
     * 而是返回 {@code spoken=false, reason=already_spoken} —— 客户端可以安全重试。
     * 场景没配开场白时返回 {@code reason=no_opening}，直接进问答。
     */
    @PostMapping("/{sessionId}/opening")
    public OpeningResponseDTO opening(@PathVariable String sessionId) {
        return conversationCase.opening(sessionId);
    }

    /**
     * 语音上行。
     *
     * body 有两种用法：
     * <ul>
     *   <li><b>空 body（主路径）</b> —— 「我说完了」，音频不传。
     *       后端会去取 WebRTC 上行缓冲里那份 —— 它过了 AEC3，
     *       数字人外放的声音已经在麦克风里被减掉，不会变成用户台词。</li>
     *   <li><b>裸 PCM（s16le / 单声道 / 16kHz）</b> —— 老路径。
     *       ⚠️ 这份音频没有回声消除，数字人说话时会被一起录进去。仅作兜底。</li>
     * </ul>
     *
     * 用 application/octet-stream 而不是 multipart：App 侧手里就是一块 ByteArray，
     * 少一层包装就少一处能出错的地方。
     * 返回识别文本，App 拿它收掉「正在识别」的转圈；用户台词本身会走 SSE 的 stt.final。
     */
    @PostMapping(value = "/{sessionId}/voice", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public Map<String, Object> voice(@PathVariable String sessionId,
                                     @RequestHeader(value = "X-Sample-Rate", defaultValue = "16000") int sampleRate,
                                     @RequestBody(required = false) byte[] pcm) {
        // 空 body 是**正常用法**，不是错误：意思是「用服务端那份过了 AEC3 的音频」。
        byte[] payload = (pcm == null || pcm.length == 0) ? null : pcm;
        String text = conversationCase.voice(sessionId, payload, sampleRate);
        return Map.of("text", text);
    }

    /** 打断当前这句。 */
    @PostMapping("/{sessionId}/interrupt")
    public Map<String, Object> interrupt(@PathVariable String sessionId) {
        conversationCase.interrupt(sessionId);
        return Map.of("status", "accepted");
    }

    /** 关会话并释放上游资源。重复调用安全（第二次什么都不做）。 */
    @DeleteMapping("/{sessionId}")
    public Map<String, Object> close(@PathVariable String sessionId) {
        conversationCase.close(sessionId);
        return Map.of("status", "closed");
    }

    /** 本场会话的历史回合（服务重启后仍在，数据来自库）。 */
    @GetMapping("/{sessionId}/turns")
    public List<TurnResponseDTO> turns(@PathVariable String sessionId) {
        return conversationCase.turns(sessionId);
    }
}
package cn.adinall.ai.types.enums;

/**
 * 数字人对话的统一响应码（迁自 spoken-ai）
 * <p>
 * 功能：把数字人对话链路上的业务失败收拢成一套稳定码，由 AppException 携带、
 * 由 GlobalExceptionHandler 映射成 HTTP 状态码与错误体 {"code":"...","message":"...","ts":...}。
 * <pre>
 *   SUCCESS                    "success"                   成功
 *   SESSION_NOT_FOUND          "session_not_found"         会话不存在或已结束      -> HTTP 404
 *   SESSION_QUEUED             "session_queued"            上游 GPU 槽位被占用      -> HTTP 409
 *   SESSION_NOT_OPEN           "session_not_open"          会话已结束，不能再上行   -> HTTP 502
 *   UPSTREAM_AUDIO_UNSUPPORTED "upstream_audio_unsupported" 后端不支持语音上行      -> HTTP 502
 *   SCRIPTED_SAY_UNSUPPORTED   "scripted_say_unsupported"   后端不支持脚本轨直念     -> HTTP 502
 *   PROVIDER_UNAVAILABLE       "provider_unavailable"       连不上上游数字人服务     -> HTTP 503
 *   NO_SPEECH                  "no_speech"                 音频里没有人声（正常）    -> HTTP 400
 *   PRONUNCIATION_FAILED       "pronunciation_failed"       评测没拿到可用结果       -> HTTP 502
 *   AUDIO_TOO_LARGE            "audio_too_large"            上传音频超过上限         -> HTTP 413
 *   INVALID_REQUEST            "invalid_request"           入参不合法              -> HTTP 400
 *   NOT_FOUND                  "not_found"                 静态资源/路径不存在      -> HTTP 404
 *   INTERNAL_ERROR             "internal_error"            兜底                    -> HTTP 500
 *   getCode()                  取 code 字面量（写进响应体）
 *   getMessage()               取默认文案（调用方通常覆盖成更具体的一句）
 * </pre>
 * 运行过程（异常 -> HTTP 的映射链）：
 * <pre>
 *   ConversationServiceImpl.requireSession(sessionId)
 *        | 查不到会话
 *        v
 *   throw new AppException(ResponseCodeEnum.SESSION_NOT_FOUND, "会话不存在或已结束: s-001")
 *        |
 *        v
 *   GlobalExceptionHandler.handleAppException(e)
 *        |
 *        | statusOf(e.code()):
 *        |   provider_unavailable -> 503
 *        |   session_not_found    -> 404
 *        |   session_queued       -> 409
 *        |   其余                  -> 502
 *        v
 *   HTTP 404  body = {"code":"session_not_found","message":"会话不存在或已结束: s-001","ts":...}
 * </pre>
 * 案例：
 * <pre>
 *   输入: GET /api/v1/sessions/s-001（会话已被关掉）
 *   处理: requireSession 抛 AppException(SESSION_NOT_FOUND, "会话不存在或已结束: s-001")
 *   输出: 404 {"code":"session_not_found","message":"会话不存在或已结束: s-001","ts":1767225600000}
 *         上游排队: 409 {"code":"session_queued",
 *                        "message":"数字人后端还在排队（GPU 槽位被占用），请稍后重试",...}
 *         上游连不上: 503 {"code":"provider_unavailable",
 *                         "message":"OpenTalking 请求失败: /sessions/ot-7f3a",...}
 *         参数校验失败: 400 {"code":"invalid_request","message":"text must not be blank",...}
 *   说明: 到客户端的 message 是抛出点自己传的那句话，本枚举第二个参数只是「调用方没传时的默认值」——
 *         provider_unavailable 的抛出点都带着具体描述，所以默认文案「数字人服务不可用」不会出现在响应里；
 *         code 才是稳定契约，客户端一律按 code 分支。
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>取值就是 App 在错误体里拿到的字符串，改成别的字面量等于改了对外契约 ——
 *       所以这里只做「把散落各处的魔法字符串收拢」，不做重命名。</li>
 *   <li>只收录「稳定且被 HTTP 状态码映射引用」的那几个：像 ot_http_500 / sse_http_404 这种
 *       带运行时数字的码继续用 AppException 的字符串构造器，塞进枚举只会得到一个随时在长的枚举。</li>
 *   <li>与 {@link ResponseCode}（0000 / E0001）是两套码：那套是 Agent 脚手架的契约（配 Response 外壳、
 *       HTTP 恒 200），本枚举是数字人对话的契约（配 HTTP 状态码），混用会让 App 的判码分支对不上。</li>
 *   <li>GlobalExceptionHandler 用本枚举的 getCode() 做比较而不是再抄一遍字符串：
 *       这几个码是对外契约，写死在两处迟早对不上。</li>
 * </ul>
 */
public enum ResponseCodeEnum {

    SUCCESS("success", "成功"),

    /** 会话不存在，或已经被关掉了 -> HTTP 404 */
    SESSION_NOT_FOUND("session_not_found", "会话不存在或已结束"),
    /** 数字人后端单 GPU 槽还没腾出来 -> HTTP 409 */
    SESSION_QUEUED("session_queued", "数字人后端还在排队（GPU 槽位被占用）"),
    /** 会话已经结束，不能再上行 -> HTTP 502 */
    SESSION_NOT_OPEN("session_not_open", "会话已结束"),
    /** 用了一个不收上行音频的后端实现 -> HTTP 502 */
    UPSTREAM_AUDIO_UNSUPPORTED("upstream_audio_unsupported", "当前数字人后端不支持语音上行"),
    /** 用了一个不支持脚本轨直念的后端实现；用例层应改用触发词让 LLM 生成 -> HTTP 502 */
    SCRIPTED_SAY_UNSUPPORTED("scripted_say_unsupported", "当前数字人后端不支持脚本轨直念"),

    /** 上传的音频里没有可识别的人声 —— **正常结果，不是故障**（AEC 把数字人的声音消掉后剩下的就是静音）-> HTTP 400 */
    NO_SPEECH("no_speech", "没有识别出有效语音"),
    /** 连不上上游数字人服务（网络层失败，不是业务失败）-> HTTP 503 */
    PROVIDER_UNAVAILABLE("provider_unavailable", "数字人服务不可用"),

    /** 发音评测跑完了但没拿到可用结果（上游返回了空/非法内容）-> HTTP 502 */
    PRONUNCIATION_FAILED("pronunciation_failed", "发音评测失败"),
    /** 上传的音频超过上游可接受的大小上限 -> HTTP 413 */
    AUDIO_TOO_LARGE("audio_too_large", "音频文件过大"),

    /** 入参不合法 -> HTTP 400 */
    INVALID_REQUEST("invalid_request", "请求参数不合法"),
    /** 静态资源/路径不存在 -> HTTP 404 */
    NOT_FOUND("not_found", "资源不存在"),
    /** 兜底 -> HTTP 500 */
    INTERNAL_ERROR("internal_error", "服务内部错误");

    private final String code;
    private final String message;

    /** 创建 {@code ResponseCodeEnum}，初始化其依赖与状态。 */
    ResponseCodeEnum(String code, String message) {
        this.code = code;
        this.message = message;
    }

    /** 对外的 code 字面量，直接写进错误体。 */
    public String getCode() {
        return code;
    }

    /** 默认提示文案；调用方通常会覆盖成更具体的一句。 */
    public String getMessage() {
        return message;
    }
}

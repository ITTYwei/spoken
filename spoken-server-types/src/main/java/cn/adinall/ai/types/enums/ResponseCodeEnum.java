package cn.adinall.ai.types.enums;

/**
 * 数字人对话的统一响应码（迁自 spoken-ai）。
 *
 * 取值就是 App 在 HTTP 错误体 {"code": "...", "message": "..."} 里拿到的那个字符串，
 * 改成别的字面量等于改了对外契约 —— 所以这里只做「把散落各处的魔法字符串收拢」，不做重命名。
 *
 * 只收录「稳定且被 HTTP 状态码映射引用」的那几个。像 ot_http_500 / sse_http_404 这种
 * 带运行时数字的码，继续用 AppException 的字符串构造器，不必塞进枚举。
 *
 * 注意：这里与 {@link cn.adinall.ai.types.enums.ResponseCode} 是两套码。
 * ResponseCode（0000/E0001）是 Agent 脚手架的契约；本枚举是数字人对话的契约，互不混用。
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

    /** 连不上上游数字人服务（网络层失败，不是业务失败）-> HTTP 503 */
    PROVIDER_UNAVAILABLE("provider_unavailable", "数字人服务不可用"),

    /** 入参不合法 -> HTTP 400 */
    INVALID_REQUEST("invalid_request", "请求参数不合法"),
    /** 静态资源/路径不存在 -> HTTP 404 */
    NOT_FOUND("not_found", "资源不存在"),
    /** 兜底 -> HTTP 500 */
    INTERNAL_ERROR("internal_error", "服务内部错误");

    private final String code;
    private final String message;

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

package cn.adinall.ai.types.exception;

import cn.adinall.ai.types.enums.ResponseCodeEnum;

/**
 * 全链路统一业务异常（types 模块唯一的业务异常类型）
 * <p>
 * 功能：用一个异常类承载「错误码 + 提示文案」，把两套历史用法（Agent 脚手架约定与
 * 迁入的数字人对话代码）兼容在同一个 code 字段上。
 * <pre>
 *   AppException("E0001")                                    Agent 侧：info 默认等于 code
 *   AppException("E0001", cause)                             带原因的 Agent 侧用法
 *   AppException("E0001", "智能体ID不存在")                    显式给 code 与 info
 *   AppException("E0001", "智能体ID不存在", cause)             再带原因
 *   AppException(ResponseCodeEnum.SESSION_NOT_FOUND, "msg")   对话侧：code 从枚举取
 *   AppException(ResponseCodeEnum.X, "msg", cause)            对话侧 + 原因
 *   getCode()   异常码（Agent 侧约定，等价于 code()）
 *   getInfo()   异常信息（Agent 侧约定）
 *   code()      异常码（对话侧约定，等价于 getCode()）
 * </pre>
 * 体系架构 / 运行过程：
 * <pre>
 *   domain / case / infrastructure -> AppException(code, info)
 *       -> GlobalExceptionHandler -> HTTP status + {code, message, ts}
 *   session_not_found -> 404; provider_unavailable -> 503
 * </pre>
 * 案例：
 * <pre>
 *   输入: new AppException(ResponseCodeEnum.SESSION_NOT_FOUND, "会话不存在或已结束: s-001")
 *   处理: code="session_not_found"；super(info, cause) 把文案写进 detailMessage
 *   输出: getCode()/code() = "session_not_found"，getInfo()/getMessage() = "会话不存在或已结束: s-001"
 *         toString() = AppException{code='session_not_found', info='会话不存在或已结束: s-001'}
 *         经全局处理器后: 404 {"code":"session_not_found","message":"会话不存在或已结束: s-001"}
 *   另一例: new AppException("E0001") 的 getInfo() 与 getCode() 都是 "E0001"
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>全链路只有这一种业务异常：GlobalExceptionHandler 只做「按 code 映射 HTTP 状态」，
 *       不做「按异常类型分门别类」，所以新增一种业务失败不必新增异常类。</li>
 *   <li>两个读取入口（getCode/getInfo 与 code/getMessage）指向同一个 code 字段：
 *       这是为了兼容两批代码，对外统一由全局异常处理器返回 HTTP 状态与错误体。</li>
 *   <li>继承 RuntimeException（非受检）：调用方不必到处声明 throws，但漏 catch 就会一路冒到
 *       全局处理器；只有真的想让它变成 HTTP 错误码时才抛它。</li>
 *   <li>message 通过 super(info, cause) 写进 detailMessage：日志里打 getMessage() 能直接看到
 *       可读文案，同时 cause 保留原始堆栈；toString 也刻意只打 code/info，避免日志刷一整棵堆栈。</li>
 * </ul>
 */
public class AppException extends RuntimeException {

    private static final long serialVersionUID = 5317680961212299217L;

    private final String code;
    private final String info;

    /** 创建 {@code AppException}，初始化其依赖与状态。 */
    public AppException(String code) {
        this(code, code, null);
    }

    /** 创建 {@code AppException}，初始化其依赖与状态。 */
    public AppException(String code, Throwable cause) {
        this(code, code, cause);
    }

    /** 创建 {@code AppException}，初始化其依赖与状态。 */
    public AppException(String code, String info) {
        this(code, info, null);
    }

    /** 创建 {@code AppException}，初始化其依赖与状态。 */
    public AppException(String code, String info, Throwable cause) {
        super(info, cause);
        this.code = code;
        this.info = info;
    }

    /** 创建 {@code AppException}，初始化其依赖与状态。 */
    public AppException(ResponseCodeEnum responseCode, String message) {
        this(responseCode.getCode(), message, null);
    }

    /** 创建 {@code AppException}，初始化其依赖与状态。 */
    public AppException(ResponseCodeEnum responseCode, String message, Throwable cause) {
        this(responseCode.getCode(), message, cause);
    }

    /** 异常码（Agent 侧约定）。 */
    public String getCode() {
        return code;
    }

    /** 异常信息（Agent 侧约定）。 */
    public String getInfo() {
        return info;
    }

    /** 异常码（对话侧约定，等价于 {@link #getCode()}）。 */
    public String code() {
        return code;
    }

    /** 返回当前对象的文本表示。 */
    @Override
    public String toString() {
        return "AppException{code='" + code + "', info='" + info + "'}";
    }
}

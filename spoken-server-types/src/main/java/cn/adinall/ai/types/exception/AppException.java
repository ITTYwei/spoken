package cn.adinall.ai.types.exception;

import cn.adinall.ai.types.enums.ResponseCodeEnum;

/**
 * 全链路统一业务异常。
 *
 * 兼容两套用法（Agent 脚手架原约定 + 迁入的数字人对话代码）：
 * <ul>
 *   <li>Agent 侧：{@code new AppException("E0001")}，读 {@code getCode()} / {@code getInfo()}</li>
 *   <li>对话侧：{@code new AppException(ResponseCodeEnum.X, "msg")} 或
 *       {@code new AppException("ot_http_400", "msg")}，读 {@code code()} / {@code getMessage()}</li>
 * </ul>
 *
 * 两者共用同一个 {@code code} 字段，只是提供了两组读取入口。message 同时写进
 * {@link RuntimeException} 的 detailMessage，所以 {@code getMessage()} 也能拿到文案。
 */
public class AppException extends RuntimeException {

    private static final long serialVersionUID = 5317680961212299217L;

    private final String code;
    private final String info;

    public AppException(String code) {
        this(code, code, null);
    }

    public AppException(String code, Throwable cause) {
        this(code, code, cause);
    }

    public AppException(String code, String info) {
        this(code, info, null);
    }

    public AppException(String code, String info, Throwable cause) {
        super(info, cause);
        this.code = code;
        this.info = info;
    }

    public AppException(ResponseCodeEnum responseCode, String message) {
        this(responseCode.getCode(), message, null);
    }

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

    @Override
    public String toString() {
        return "AppException{code='" + code + "', info='" + info + "'}";
    }
}

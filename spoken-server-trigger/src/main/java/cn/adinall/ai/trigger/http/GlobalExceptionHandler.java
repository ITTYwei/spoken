package cn.adinall.ai.trigger.http;

import cn.adinall.ai.types.enums.ResponseCodeEnum;
import cn.adinall.ai.types.exception.AppException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 全局异常处理：把业务异常翻成 HTTP。
 *
 * 全链路只有一种业务异常（AppException），所以这里做的是「按 code 映射状态码」这一件事，
 * 而不是「按谁抛的异常分门别类」—— 异常类只有一种，映射规则只有一处。
 *
 * 响应体形状固定为 { code, message, ts }：App 拿 code 做分支，不去解析 message 文案。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 处理业务异常，按错误码映射 HTTP 状态。
     * 流程：provider_unavailable -> 503（上游连不上，不是我们的业务失败）->
     * session_not_found -> 404 -> session_queued -> 409 -> 其余 -> 502（上游调用失败）。
     */
    @ExceptionHandler(AppException.class)
    public ResponseEntity<Map<String, Object>> handleAppException(AppException e) {
        HttpStatus status = statusOf(e.code());
        log.warn("业务异常 [{}]: {}", e.code(), e.getMessage());
        return body(status, e.code(), e.getMessage());
    }

    /**
     * 处理 @Valid 参数校验失败。
     * 流程：从绑定结果里取第一条字段错误 -> 拼成「字段 + 原因」-> 返回 400 invalid_request。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .orElse("请求参数不合法");
        return body(HttpStatus.BAD_REQUEST, ResponseCodeEnum.INVALID_REQUEST.getCode(), message);
    }

    /**
     * 处理代码里主动抛的参数不合法异常，返回 400 invalid_request。
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException e) {
        return body(HttpStatus.BAD_REQUEST, ResponseCodeEnum.INVALID_REQUEST.getCode(), e.getMessage());
    }

    /**
     * SSE 客户端断开（App 切后台、拔线、curl 超时）必然抛 IOException。
     * 这时响应已经提交，再去写 JSON 错误体只会刷
     * "No converter for [...] with preset Content-Type 'text/event-stream'"。
     * 所以只记 debug，回一个不带 body 的响应。
     */
    @ExceptionHandler(IOException.class)
    public ResponseEntity<Void> handleClientAbort(IOException e) {
        log.debug("连接中断（多为 SSE 客户端断开）: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }

    /** 访问不存在的静态路径（/、/favicon.ico）是正常 404，不该打 ERROR 堆栈。 */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNoResource(NoResourceFoundException e) {
        return body(HttpStatus.NOT_FOUND, ResponseCodeEnum.NOT_FOUND.getCode(), e.getMessage());
    }

    /**
     * 兜底处理所有没被上面接住的异常。
     * 流程：打 ERROR 全堆栈 -> 返回 500 internal_error，把 message 一并带给客户端便于定位。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleOther(Exception e) {
        log.error("未处理异常", e);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, ResponseCodeEnum.INTERNAL_ERROR.getCode(), e.getMessage());
    }

    /**
     * 错误码 -> HTTP 状态码。
     * 用 ResponseCodeEnum 里的字面量做比较而不是再抄一遍字符串：这几个码是对外契约，
     * 改一处就要两边一起改，写死在两处迟早对不上。
     */
    private static HttpStatus statusOf(String code) {
        if (ResponseCodeEnum.PROVIDER_UNAVAILABLE.getCode().equals(code)) {
            return HttpStatus.SERVICE_UNAVAILABLE;
        }
        if (ResponseCodeEnum.SESSION_NOT_FOUND.getCode().equals(code)) {
            return HttpStatus.NOT_FOUND;
        }
        if (ResponseCodeEnum.SESSION_QUEUED.getCode().equals(code)) {
            return HttpStatus.CONFLICT;
        }
        return HttpStatus.BAD_GATEWAY;
    }

    /**
     * 构造统一的错误响应体，字段固定为 code / message / ts。
     * 流程：message 为 null 时归一成空串，避免前端拿到 null 又要判一次。
     */
    private static ResponseEntity<Map<String, Object>> body(HttpStatus status, String code, String message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", code);
        payload.put("message", message == null ? "" : message);
        payload.put("ts", System.currentTimeMillis());
        return ResponseEntity.status(status).body(payload);
    }
}
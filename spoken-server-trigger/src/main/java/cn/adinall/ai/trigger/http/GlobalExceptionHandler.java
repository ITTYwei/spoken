package cn.adinall.ai.trigger.http;

import cn.adinall.ai.types.enums.ResponseCodeEnum;
import cn.adinall.ai.types.exception.AppException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 全局异常处理（@RestControllerAdvice）：把异常统一翻成 HTTP 状态码与固定形状的错误体
 * <p>
 * 功能：全链路只有一种业务异常（AppException），所以这里做的是「按 code 映射状态码」这一件事，
 * 而不是「按谁抛的异常分门别类」—— 异常类只有一种，映射规则只有一处。
 * 响应体形状固定为 { code, message, ts }：App 拿 code 做分支，不去解析 message 文案。
 * <pre>
 *   handleAppException(AppException)                     业务异常：按 code 映射 404 / 409 / 400 / 413 / 502 / 503
 *   handleValidation(MethodArgumentNotValidException)    @Valid 失败：400 invalid_request
 *   handleIllegalArgument(IllegalArgumentException)       代码里主动抛的参数错误：400 invalid_request
 *   handleMissingParam(MissingServletRequest*Exception)  少传必填 part/参数：400 invalid_request
 *   handleTooLarge(MaxUploadSizeExceededException)       上传体积超限：413 audio_too_large
 *   handleClientAbort(IOException)                        SSE 客户端断开：500 且不带响应体
 *   handleNoResource(NoResourceFoundException)            静态路径不存在：404 not_found
 *   handleOther(Exception)                                兜底：500 internal_error
 *   statusOf(code)                                        错误码 -> HttpStatus（私有）
 *   body(status, code, message)                           拼 { code, message, ts }（私有）
 * </pre>
 * 运行过程：
 * <pre>
 *   任意 Controller 抛出异常
 *        |
 *        | (1) AppException --> statusOf(e.code())
 *        |        provider_unavailable -> 503 SERVICE_UNAVAILABLE
 *        |        session_not_found    -> 404 NOT_FOUND
 *        |        session_queued       -> 409 CONFLICT
 *        |        invalid_request      -> 400 BAD_REQUEST
 *        |        audio_too_large      -> 413 PAYLOAD_TOO_LARGE
 *        |        其余                  -> 502 BAD_GATEWAY
 *        |        log.warn("业务异常 [code]: message")
 *        |
 *        | (2) MethodArgumentNotValidException --> 400，取第一条字段错误拼成「字段 + 原因」
 *        | (3) IllegalArgumentException       --> 400 invalid_request
 *        | (4) MissingServletRequestPart/ParameterException --> 400 invalid_request（少传必填字段）
 *        | (5) MaxUploadSizeExceededException --> 413 audio_too_large
 *        | (6) IOException                    --> 500，空 body（只记 debug）
 *        | (7) NoResourceFoundException       --> 404 not_found
 *        | (8) Exception                      --> log.error 全堆栈 + 500 internal_error
 *        v
 *   ResponseEntity.status(status).body(LinkedHashMap{code, message, ts})
 * </pre>
 * 案例：
 * <pre>
 *   输入: conversationService.requireSession("s-404") 抛
 *         AppException(ResponseCodeEnum.SESSION_NOT_FOUND, "会话不存在或已结束: s-404")
 *   处理: statusOf("session_not_found") -> 404；message 非空原样带出
 *   输出: 404 {"code":"session_not_found","message":"会话不存在或已结束: s-404","ts":1767225600000}
 *   其它分支（message 一律取自抛出时给的描述，枚举里的默认文案在这条路径上到不了客户端）:
 *         上游排队     -> 409 {"code":"session_queued",
 *                              "message":"数字人后端还在排队（GPU 槽位被占用），请稍后重试",...}
 *         上游连不上   -> 503 {"code":"provider_unavailable",
 *                              "message":"OpenTalking 请求失败: /sessions/ot-7f3a",...}
 *                              （同类抛出点还有「OpenTalking 请求被中断: &lt;uri&gt;」
 *                               「OpenTalking 事件流不可达: &lt;base&gt;」「OpenTalking 事件流被中断」）
 *         上游其它失败 -> 502（statusOf 的兜底）
 *         入参校验失败 -> 400 {"code":"invalid_request","message":"text must not be blank",...}
 *         SSE 断开     -> 500 且没有响应体（响应已提交，写 JSON 只会刷转换器报错）
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>按 code 而不是按异常子类映射：全链路只有 AppException 一种业务异常，
 *       再加一种业务失败只需加一个码，不用加异常类也不用改这里的分支结构。</li>
 *   <li>statusOf 用 ResponseCodeEnum 的常量做比较而不是再抄一遍字符串字面量：
 *       这几个码是对外契约，写死在两处迟早对不上。</li>
 *   <li>IOException 单独接住：SSE 客户端断开（切后台、拔线、curl 超时）必然抛它，
 *       而那时响应已提交，再写 JSON 错误体只会刷出
 *       "No converter for [...] with preset Content-Type 'text/event-stream'"，所以只 debug 记一笔。</li>
 *   <li>NoResourceFoundException 单独接住：访问 / 或 /favicon.ico 是正常 404，
 *       落到兜底分支会打一整条 ERROR 堆栈，把真实故障淹没。</li>
 *   <li>业务异常用 warn、未知异常用 error：日志级别本身就是「这算不算故障」的声明。</li>
 *   <li>message 为 null 时归一成空串：避免前端拿到 null 还要再判一次；ts 每次现取，
 *       便于和后端的日志时间对齐排查。</li>
 * </ul>
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
     * 处理「请求里少了必填字段」：multipart 少一个 part、或查询/表单少一个参数。
     *
     * 这两种都是客户端把请求拼错了，属于 400；不接住的话会落到兜底分支返回 500，
     * 让 App 以为是自己后端挂了（跟读评测的 file/expectedText 漏传就会走到这里）。
     */
    @ExceptionHandler({MissingServletRequestPartException.class, MissingServletRequestParameterException.class})
    public ResponseEntity<Map<String, Object>> handleMissingParam(Exception e) {
        return body(HttpStatus.BAD_REQUEST, ResponseCodeEnum.INVALID_REQUEST.getCode(), e.getMessage());
    }

    /**
     * 处理上传体积超限（spring.servlet.multipart.max-file-size）。
     *
     * 这个异常在进 Controller 之前就抛了，所以拿不到我们自己的「音频超过上限」那句文案；
     * 映射成 413 与领域层 audio_too_large 的语义保持一致，App 只需按 413 一种情况处理。
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handleTooLarge(MaxUploadSizeExceededException e) {
        log.warn("上传体积超限: {}", e.getMessage());
        return body(HttpStatus.PAYLOAD_TOO_LARGE, ResponseCodeEnum.AUDIO_TOO_LARGE.getCode(),
                "上传文件超过大小上限，请缩短录音后重试");
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
     *
     * INVALID_REQUEST 与 AUDIO_TOO_LARGE 是给「领域层主动抛出的入参错误」用的：
     * @Valid 校验失败走的是另一个分支（handleValidation），但领域层（如发音评测端口）
     * 也会自己挡非法的音频/题面 —— 那些也是 400/413，不该落到 502 的兜底上去。
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
        if (ResponseCodeEnum.INVALID_REQUEST.getCode().equals(code)) {
            return HttpStatus.BAD_REQUEST;
        }
        // 「音频里没人声」是正常结果，不是上游故障：把它判成 502 会让客户端把
        // 「你没说话」显示成「服务器挂了」——真有这类把正常当故障的误报。
        if (ResponseCodeEnum.NO_SPEECH.getCode().equals(code)) {
            return HttpStatus.BAD_REQUEST;
        }
        if (ResponseCodeEnum.AUDIO_TOO_LARGE.getCode().equals(code)) {
            return HttpStatus.PAYLOAD_TOO_LARGE;
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
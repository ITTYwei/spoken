package cn.adinall.ai.api.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 统一响应外壳（Agent 那组 HTTP 接口的返回形状）
 * <p>
 * 功能：把「业务码 + 提示文案 + 数据」装成一个固定三字段的对象，
 * 让 HTTP 状态码始终是 200、由 code 表达成败。
 * <pre>
 *   code   业务码字符串；成功固定 "0000"（ResponseCode.SUCCESS），失败用异常自带的 code
 *   info   提示文案；成功是 "成功"，失败是异常信息或 "未知失败"
 *   data   业务数据；失败时为 null
 * </pre>
 * 运行过程：
 * <pre>
 *   AgentServiceController.queryAiAgentConfigList()
 *        |
 *        | (1) try: 调 IChatService，把领域对象映射成 DTO
 *        |        |
 *        |        v
 *        |     Response.&lt;List&lt;AiAgentConfigResponseDTO&gt;&gt;builder()
 *        |         .code(ResponseCode.SUCCESS.getCode())    // "0000"
 *        |         .info(ResponseCode.SUCCESS.getInfo())    // "成功"
 *        |         .data(responseDTOS).build()
 *        |
 *        | (2) catch AppException e: code=e.getCode(), info=e.getInfo()，不带 data
 *        | (3) catch Exception e:   code="0001"，info="未知失败"
 *        v
 *   返回给 App，App 先看 code 再取 data
 * </pre>
 * 案例：
 * <pre>
 *   输入: GET /api/v1/query_ai_agent_config_list
 *   处理: 组装成功响应
 *   输出: {"code":"0000","info":"成功","data":[{"agentId":"3001","agentName":"对话助手",
 *          "agentDesc":"通用问答智能体"}]}
 *         失败时: {"code":"E0001","info":"E0001","data":null}
 *                 {"code":"0001","info":"未知失败","data":null}
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>用 @Builder 而不是 new：这个类要 new 的地方都在 try/catch 里，
 *       链式写一次就能把「成功」与「失败」两支都写清楚，也不会有漏设字段的半成品。</li>
 *   <li>data 是泛型 T：同一个外壳可以装 List、单个 DTO 或任何东西，
 *       App 侧只需要一套「先判 code」的解析代码。</li>
 *   <li>注意本项目有两套码：本类装的是 ResponseCode（0000 / 0001 / E0001，
 *       Agent 脚手架契约）或 AppException 自带的码；数字人对话那组走 ResponseCodeEnum
 *       （success / session_not_found）并由 GlobalExceptionHandler 映射 HTTP 状态码，两者不要混用。</li>
 *   <li>失败时 data 为 null 且没有 @JsonInclude：JSON 里会出现 "data":null，
 *       App 判空时要按「字段在但值为 null」处理。</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Response<T> implements Serializable {

    private static final long serialVersionUID = 7000723935764546321L;

    private String code;
    private String info;
    private T data;

}

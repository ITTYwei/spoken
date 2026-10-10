package cn.adinall.ai.api.dto;

import lombok.Data;

/**
 * 对话请求（POST /api/v1/chat 与 POST /api/v1/chat_stream 共用）
 * <p>
 * 功能：描述「一轮对话」所需的最小信息——跟哪个智能体、以哪个用户身份、在哪场会话里、说什么。
 * <pre>
 *   agentId    跟哪个智能体对话；装配期没注册出这个 id 时，运行期取 bean 会抛 E0001
 *   userId     用户标识，用于会话归属与落库
 *   sessionId  可空；为空或空串时 Controller 会先调 createSession 补一场新会话
 *   message    这一轮用户说的话
 * </pre>
 * 运行过程：
 * <pre>
 *   POST /api/v1/chat
 *        body = {"agentId":"3001","userId":"u-001","sessionId":"","message":"你好"}
 *        |
 *        v
 *   Jackson 反序列化成 ChatRequestDTO
 *        |
 *        | (1) sessionId 为 null / 空串 --> chatService.createSession(agentId, userId)
 *        | (2) chatService.handleMessage(agentId, userId, sessionId, message)
 *        |        |
 *        |        v
 *        |     List&lt;String&gt; messages --> String.join("\n", messages)
 *        v
 *   Response&lt;ChatResponseDTO&gt;.data.content = 拼好的整段回复
 * </pre>
 * 案例：
 * <pre>
 *   输入: {"agentId":"3001","userId":"u-001","sessionId":"","message":"你好"}
 *   处理: 自动建会话得到 sessionId，再用它跑一轮 handleMessage
 *   输出: {"code":"0000","info":"成功","data":{"content":"你好，有什么可以帮你？"}}
 *         agentId 不存在: {"code":"E0001","info":"E0001","data":null}
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>sessionId 做成「可空 + 自动补建」：App 首次对话不必先单独调一次 create_session，
 *       少一次往返；代价是建会话失败会直接让这次对话失败。</li>
 *   <li>用 @Data 可变类而不是 record：Controller 需要先 new 再 set 来复用
 *       create_session 的 GET 重载（它把两个 @RequestParam 塞回本对象）。</li>
 *   <li>字段不加 @NotBlank：这条老链路的契约是「缺参数由下层抛 AppException 并映射成业务码」，
 *       而不是 400 参数校验；新写的会话接口才用 jakarta.validation。</li>
 * </ul>
 */
@Data
public class ChatRequestDTO {

    private String agentId;
    private String userId;
    private String sessionId;
    private String message;

}

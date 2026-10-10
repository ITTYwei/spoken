package cn.adinall.ai.api.dto;

import lombok.Data;

/**
 * 建会话响应对象（POST / GET create_session 的出参元素）
 * <p>
 * 功能：把新建会话的 id 交给前端，后续 chat / chat_stream 都靠它续上同一场上下文。
 * <pre>
 *   getSessionId()  新会话的唯一标识；chat 请求把它回填进 ChatRequestDTO.sessionId
 * </pre>
 * 运行过程：
 * <pre>
 *   POST /api/v1/create_session  {"agentId":"3001","userId":"u-001"}
 *        |
 *        v
 *   AgentServiceController.createSession(CreateSessionRequestDTO)
 *        |
 *        | (1) chatService.createSession(agentId, userId) --> sessionId
 *        | (2) new CreateSessionResponseDTO + setSessionId
 *        v
 *   Response{code="0000", info="成功", data={sessionId="..."}}
 * </pre>
 * 案例：
 * <pre>
 *   输入: {"agentId":"3001","userId":"u-001"}
 *   处理: chatService.createSession("3001","u-001")
 *   输出: {"code":"0000","info":"成功","data":{"sessionId":"3f2b9c1e-..."}}
 *         agentId 取不到会话上下文: {"code":"E0001","info":"E0001","data":null}
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>会话这个「有状态的东西」必须由服务端发号：id 由 ChatService 生成，
 *       前端拿到的只能是结果，不能自己造，否则多端并发的会话归属会对不上。</li>
 *   <li>只回 sessionId、不回 agentId/userId：这两个值调用方本来就有，
 *       回显只会让「谁才是真正生效的身份」变得含糊。</li>
 *   <li>建会话本身无副作用要求，但它是后续所有对话的前置条件 —— 失败时 data 为 null，
 *       前端应直接提示重试，而不是拿空 id 继续调 chat。</li>
 * </ul>
 */
@Data
public class CreateSessionResponseDTO {

    private String sessionId;

}

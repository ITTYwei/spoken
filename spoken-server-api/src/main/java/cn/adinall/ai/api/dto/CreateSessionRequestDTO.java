package cn.adinall.ai.api.dto;

import lombok.Data;

/**
 * 建会话请求（POST /api/v1/create_session 的入参，GET 重载也复用它）
 * <p>
 * 功能：告诉服务端「以哪个用户的身份，和哪个智能体开一场会话」。
 * <pre>
 *   agentId  智能体 id；必须是装配期注册过的那一个，否则取 bean 时抛 E0001
 *   userId   用户标识；ChatService 按它做会话归属（同一个 userId 复用同一场会话）
 * </pre>
 * 运行过程：
 * <pre>
 *   POST /api/v1/create_session   body = {"agentId":"3001","userId":"u-001"}
 *   或
 *   GET  /api/v1/create_session?agentId=3001&amp;userId=u-001
 *        |
 *        v
 *   AgentServiceController.createSession(...)
 *        |
 *        | GET 重载：把两个 @RequestParam 塞进本对象再调 POST 版本
 *        | (1) chatService.createSession(agentId, userId)
 *        |        |
 *        |        v
 *        |     getAiAgentRegisterVO(agentId) 取不到 --> 抛 AppException(E0001)
 *        |     userSessions.computeIfAbsent(userId, ...) --> runner 建 Session --> sessionId
 *        v
 *   Response{code="0000", info="成功", data={sessionId:"..."}}
 * </pre>
 * 案例：
 * <pre>
 *   输入: {"agentId":"3001","userId":"u-001"}
 *   处理: chatService.createSession("3001","u-001") 用 InMemoryRunner 建一场 ADK 会话
 *   输出: {"code":"0000","info":"成功","data":{"sessionId":"3f2b9c1e-..."}}
 *         agentId 没装配过: {"code":"E0001","info":"E0001","data":null}
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>同一个 userId 复用同一场会话（computeIfAbsent）：旧链路是「按用户一段长期上下文」，
 *       不是「每次请求开一场」；要一场一会的语义请用 /api/v1/sessions 那组接口。</li>
 *   <li>用 @Data 可变类而不是 record，是为了让 GET 重载能 new 出来再 set 两个参数，
 *       两套 HTTP 方法共用同一段业务代码。</li>
 *   <li>没有必填校验：缺 agentId 会走到 AppException(E0001) 而不是 400 ——
 *       这是老链路的既有契约，改成参数校验会让 App 的错误处理分支变化。</li>
 * </ul>
 */
@Data
public class CreateSessionRequestDTO {

    private String agentId;

    private String userId;

}

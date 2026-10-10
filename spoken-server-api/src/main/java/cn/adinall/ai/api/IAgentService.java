package cn.adinall.ai.api;

import cn.adinall.ai.api.dto.*;
import cn.adinall.ai.api.response.Response;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.util.List;

/**
 * 智能体 HTTP 服务端口（api 模块对外暴露的第一组契约）
 * <p>
 * 功能：声明「列智能体配置 + 建会话 + 对话」三类动作的入参出参形状，由触发层的
 * AgentServiceController 用 Spring MVC 实现，路由前缀是 /api/v1/。
 * <pre>
 *   queryAiAgentConfigList()  列已装配的智能体配置（GET  query_ai_agent_config_list）
 *   createSession(dto)        按 agentId 建一场会话，返回 sessionId（POST create_session）
 *   chat(dto)                 一问一答，返回整段回复文本（POST chat）
 *   chatStream(dto)           流式一问一答，边生成边推（POST chat_stream）
 * </pre>
 * 运行过程（以 chat 为例）：
 * <pre>
 *   App
 *        |
 *        | POST /api/v1/chat  {"agentId":"3001","userId":"u-001","message":"你好"}
 *        v
 *   AgentServiceController.chat(ChatRequestDTO)      &lt;-- 本接口的实现类
 *        |
 *        | (1) requestDTO.sessionId 为空 --> chatService.createSession(agentId, userId)
 *        | (2) chatService.handleMessage(agentId, userId, sessionId, message)
 *        |        |
 *        |        v
 *        |     ChatService 从容器取 AiAgentRegisterVO，拿 InMemoryRunner 跑一轮
 *        v
 *   Response&lt;ChatResponseDTO&gt;{code="0000", info="成功", data={content="..."}}
 * </pre>
 * 案例：
 * <pre>
 *   输入: ChatRequestDTO{agentId="3001", userId="u-001", sessionId="", message="你好"}
 *   处理: 先建会话拿到 sessionId，再调 handleMessage 取回复并 String.join("\n", messages)
 *   输出: Response{code="0000", info="成功", data=ChatResponseDTO{content="你好，有什么可以帮你？"}}
 *         失败: agentId 取不到 bean 时 Response{code="E0001", info="E0001", data=null}
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>接口留在 api 模块、实现留在 trigger 模块：别处（定时任务、MCP、gRPC）想复用这套动作时
 *       只依赖 api 即可，不必把 Spring MVC 一起拖进来。</li>
 *   <li>chatStream 的返回类型选 ResponseBodyEmitter 而不是 Flux：走 Servlet 异步 + SSE 长连接，
 *       实现里给了 3 分钟超时，到点由 Spring 直接收掉这个连接。</li>
 *   <li>chat 与 chatStream 共用同一个 ChatRequestDTO，区别只在「攒完再回」还是「边出边推」——
 *       想加新用法优先加方法，别往 DTO 里塞开关字段。</li>
 *   <li>实现类的契约是「HTTP 200 + Response.code 表意」：异常在实现里被 catch 成 Response，
 *       所以调用方看 HTTP 状态码是看不出业务失败的，必须读 code。</li>
 * </ul>
 *
 * @author 魏玉石
 * 2026/1/20 08:16
 */
public interface IAgentService {

    Response<List<AiAgentConfigResponseDTO>> queryAiAgentConfigList();

    Response<CreateSessionResponseDTO> createSession(CreateSessionRequestDTO requestDTO);

    Response<ChatResponseDTO> chat(ChatRequestDTO requestDTO);

    ResponseBodyEmitter chatStream(ChatRequestDTO requestDTO);

}

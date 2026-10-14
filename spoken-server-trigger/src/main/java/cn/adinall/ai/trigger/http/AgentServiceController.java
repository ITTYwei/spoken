package cn.adinall.ai.trigger.http;

import cn.adinall.ai.api.IAgentService;
import cn.adinall.ai.api.dto.*;
import cn.adinall.ai.api.response.Response;
import cn.adinall.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import cn.adinall.ai.domain.agent.service.IChatService;
import cn.adinall.ai.types.enums.ResponseCode;
import cn.adinall.ai.types.exception.AppException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import javax.annotation.Resource;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 智能体 HTTP 入口（IAgentService 的 Spring MVC 实现，路由前缀 /api/v1/）
 * <p>
 * 功能：把 Agent 那组老接口翻译成对 IChatService 的调用，并统一用 Response 外壳包装结果。
 * <pre>
 *   GET  query_ai_agent_config_list   列已装配的智能体配置
 *   POST create_session               建会话（body 传 agentId / userId）
 *   GET  create_session               建会话的 GET 重载（query 参数），方便浏览器与 curl 调试
 *   POST chat                         一问一答，返回整段回复
 *   POST chat_stream                  流式一问一答，用 ResponseBodyEmitter 逐条推
 * </pre>
 * 运行过程（以 chat 为例）：
 * <pre>
 *   App
 *        |
 *        | POST /api/v1/chat  {"agentId":"3001","userId":"u-001","sessionId":"","message":"你好"}
 *        v
 *   chat(ChatRequestDTO)                      &lt;-- @RequestBody 反序列化
 *        |
 *        | (1) sessionId 为 null / 空串 --> chatService.createSession(agentId, userId)
 *        | (2) chatService.handleMessage(agentId, userId, sessionId, message) --> List&lt;String&gt;
 *        | (3) String.join("\n", messages) 装进 ChatResponseDTO
 *        | (4) Response.builder().code(SUCCESS).info(SUCCESS).data(dto).build()
 *        v
 *   HTTP 200 {"code":"0000","info":"成功","data":{"content":"..."}}
 * </pre>
 * 案例：
 * <pre>
 *   输入: POST /api/v1/chat {"agentId":"3001","userId":"u-001","message":"你好"}
 *   处理: 先建会话，再跑一轮 handleMessage
 *   输出: {"code":"0000","info":"成功","data":{"content":"你好，有什么可以帮你？"}}
 *         agentId 没装配过: {"code":"E0001","info":"E0001","data":null}
 *         其它异常:        {"code":"0001","info":"未知失败","data":null}
 *   流式输入: POST /api/v1/chat_stream（同一 DTO），响应是 text/event-stream 分片，
 *         每条取自 handleMessageStream(...).subscribe(event -> emitter.send(event.stringifyContent()))
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>异常在这里被 catch 成 Response 而不是抛出去：这组接口的契约是「HTTP 恒 200 + code 表意」，
 *       所以它不走 GlobalExceptionHandler —— 与 SessionController / CatalogController 的处理方式不同，
 *       改其中一个的异常策略时必须同时看 App 的解析逻辑。</li>
 *   <li>chatStream 返回 ResponseBodyEmitter（3 分钟超时）而不是 Flux：走 Servlet 异步，
 *       由 Spring 负责把每条 send 的内容写成 SSE；出错用 completeWithError 收尾，不写 JSON 错误体。</li>
 *   <li>GET create_session 只是调试便利：内部 new 出 DTO 再复用 POST 的那段代码，
 *       业务逻辑只有一份；新增字段时两处都要能表达。</li>
 *   <li>类上同时有 @CrossOrigin(origins = "*") 和 app 模块的 WebCorsConfiguration：
 *       两套 CORS 都会生效，调整放行来源时不要只改一边。</li>
 * </ul>
 *
 * @author 魏玉石
 * 2026/1/20 08:23
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/")
@CrossOrigin(origins = "*")
public class AgentServiceController implements IAgentService {

    @Resource
    private IChatService chatService;

    @RequestMapping(value = "query_ai_agent_config_list", method = RequestMethod.GET)
    @Override
    public Response<List<AiAgentConfigResponseDTO>> queryAiAgentConfigList() {
        try {
            log.info("查询智能体配置列表");

            List<AiAgentConfigTableVO.Agent> agentConfigs = chatService.queryAiAgentConfigList();

            List<AiAgentConfigResponseDTO> responseDTOS = agentConfigs.stream().map(agentConfig -> {
                AiAgentConfigResponseDTO responseDTO = new AiAgentConfigResponseDTO();
                responseDTO.setAgentId(agentConfig.getAgentId());
                responseDTO.setAgentName(agentConfig.getAgentName());
                responseDTO.setAgentDesc(agentConfig.getAgentDesc());
                return responseDTO;
            }).collect(Collectors.toList());

            return Response.<List<AiAgentConfigResponseDTO>>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(responseDTOS)
                    .build();

        } catch (AppException e) {
            log.error("查询智能体配置列表异常", e);
            return Response.<List<AiAgentConfigResponseDTO>>builder()
                    .code(e.getCode())
                    .info(e.getInfo())
                    .build();
        } catch (Exception e) {
            log.error("查询智能体配置列表失败", e);
            return Response.<List<AiAgentConfigResponseDTO>>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @RequestMapping(value = "create_session", method = RequestMethod.POST)
    @Override
    public Response<CreateSessionResponseDTO> createSession(@RequestBody CreateSessionRequestDTO requestDTO) {
        // 通用脚手架入口也使用可信身份，禁止通过body伪造另一个用户。
        requestDTO.setUserId("user:" + cn.adinall.ai.domain.account.support.AccountContext.require().id());
        try {
            log.info("创建会话 agentId:{} userId:{}", requestDTO.getAgentId(), requestDTO.getUserId());
            String sessionId = chatService.createSession(requestDTO.getAgentId(), requestDTO.getUserId());

            CreateSessionResponseDTO responseDTO = new CreateSessionResponseDTO();
            responseDTO.setSessionId(sessionId);

            return Response.<CreateSessionResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(responseDTO)
                    .build();
        } catch (AppException e) {
            log.error("查询智能体配置列表异常", e);
            return Response.<CreateSessionResponseDTO>builder()
                    .code(e.getCode())
                    .info(e.getInfo())
                    .build();
        } catch (Exception e) {
            log.error("创建会话失败 agentId:{} userId:{}", requestDTO.getAgentId(), requestDTO.getUserId(), e);
            return Response.<CreateSessionResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @RequestMapping(value = "create_session", method = RequestMethod.GET)
    public Response<CreateSessionResponseDTO> createSession(@RequestParam("agentId") String agentId, @RequestParam("userId") String userId) {
        CreateSessionRequestDTO requestDTO = new CreateSessionRequestDTO();
        requestDTO.setAgentId(agentId);
        requestDTO.setUserId(userId);
        return createSession(requestDTO);
    }

    @RequestMapping(value = "chat", method = RequestMethod.POST)
    @Override
    public Response<ChatResponseDTO> chat(@RequestBody ChatRequestDTO requestDTO) {
        requestDTO.setUserId("user:" + cn.adinall.ai.domain.account.support.AccountContext.require().id());
        try {
            log.info("智能体对话 agentId:{} userId:{}", requestDTO.getAgentId(), requestDTO.getUserId());
            String sessionId = requestDTO.getSessionId();
            if (sessionId == null || sessionId.isEmpty()) {
                sessionId = chatService.createSession(requestDTO.getAgentId(), requestDTO.getUserId());
            }

            List<String> messages = chatService.handleMessage(requestDTO.getAgentId(), requestDTO.getUserId(), sessionId, requestDTO.getMessage());

            ChatResponseDTO responseDTO = new ChatResponseDTO();
            responseDTO.setContent(String.join("\n", messages));

            return Response.<ChatResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(responseDTO)
                    .build();
        } catch (AppException e) {
            log.error("智能体对话异常", e);
            return Response.<ChatResponseDTO>builder()
                    .code(e.getCode())
                    .info(e.getInfo())
                    .build();
        } catch (Exception e) {
            log.error("智能体对话败 agentId:{} userId:{}", requestDTO.getAgentId(), requestDTO.getUserId(), e);
            return Response.<ChatResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @RequestMapping(value = "chat_stream", method = RequestMethod.POST)
    @Override
    public ResponseBodyEmitter chatStream(@RequestBody ChatRequestDTO requestDTO) {
        requestDTO.setUserId("user:" + cn.adinall.ai.domain.account.support.AccountContext.require().id());
        ResponseBodyEmitter emitter = new ResponseBodyEmitter(3 * 60 * 1000L);
        // 客户端断线、超时与正常完成都要释放模型订阅。
        var subscription = reactor.core.Disposables.swap();
        emitter.onCompletion(subscription::dispose);
        emitter.onTimeout(subscription::dispose);
        emitter.onError(error -> subscription.dispose());
        try {
            log.info("流式对话 agentId:{} userId:{} sessionId:{} message:{}", requestDTO.getAgentId(), requestDTO.getUserId(), requestDTO.getSessionId(), requestDTO.getMessage());
            var running = chatService.handleMessageStream(requestDTO.getAgentId(), requestDTO.getUserId(), requestDTO.getSessionId(), requestDTO.getMessage())
                    .subscribe(
                            event -> {
                                try {
                                    emitter.send(event.stringifyContent());
                                } catch (Exception e) {
                                    log.error("流式对话发送失败", e);
                                    emitter.completeWithError(e);
                                    subscription.dispose();
                                }
                            },
                            emitter::completeWithError,
                            emitter::complete
                    );
            subscription.update(running::dispose);
        } catch (Exception e) {
            log.error("流式对话失败", e);
            emitter.completeWithError(e);
        }
        return emitter;
    }

}

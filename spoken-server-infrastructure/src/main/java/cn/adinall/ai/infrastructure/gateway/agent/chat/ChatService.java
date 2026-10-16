package cn.adinall.ai.infrastructure.gateway.agent.chat;

import cn.adinall.ai.domain.companion.model.entity.ChatCommandEntity;
import cn.adinall.ai.infrastructure.config.agent.AiAgentConfigTableVO;
import cn.adinall.ai.infrastructure.gateway.agent.model.AiAgentRegisterVO;
import cn.adinall.ai.infrastructure.config.agent.AiAgentAutoConfigProperties;
import cn.adinall.ai.domain.companion.service.IChatService;
import cn.adinall.ai.infrastructure.gateway.agent.armory.factory.DefaultArmoryFactory;
import cn.adinall.ai.types.enums.ResponseCode;
import cn.adinall.ai.types.exception.AppException;
import com.google.adk.events.Event;
import com.google.adk.agents.RunConfig;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <b>功能</b>：使用现有 ADK 运行器执行模型对话，集中处理文本流转换和临时会话生命周期。
 * <p><b>体系架构 / 运行过程</b></p>
 * <pre>
 * execution adapter -> ADK runner -> events -> AgentTextStream -> text Flux
 * prepared turn -> usingWhen(create session, run stream, delete session)
 * trusted context -> register -> SDK tool context -> release on termination
 * </pre>
 * <p><b>案例</b>：文本流取消 -> 取消模型请求并删除临时 ADK 会话、撤销可信工具上下文。</p>
 * <b>设计要点</b>
 * <ul><li>复用 ADK 和 Spring AI 流式能力，不新增供应商 SSE 解析。</li><li>单轮动态策略放临时会话，避免并发学员状态互相覆盖。</li><li>同步兼容接口保留；热路径创建和清理使用响应式资源管理。</li></ul>
 */
@Slf4j
@Service("agentChatGateway")
public class ChatService  {

    @Resource
    private DefaultArmoryFactory defaultArmoryFactory;

    @Resource
    private AiAgentAutoConfigProperties aiAgentAutoConfigProperties;

    /** 仅通用聊天接口使用；教学链路采用已裁剪上下文和短生命周期会话。 */
    private final Map<String, String> userSessions = new ConcurrentHashMap<>();

    /** 查询可用智能体配置。 */
    public List<AiAgentConfigTableVO.Agent> queryAiAgentConfigList() {
        Map<String, AiAgentConfigTableVO> tables = aiAgentAutoConfigProperties.getTables();

        List<AiAgentConfigTableVO.Agent> agentList = new ArrayList<>();
        if (null != tables) {
            for (AiAgentConfigTableVO vo : tables.values()) {
                if (null != vo.getAgent()) {
                    agentList.add(vo.getAgent());
                }
            }
        }

        return agentList;
    }

    /** 创建会话并返回会话标识或初始状态。 */
    public String createSession(String agentId, String userId) {
        AiAgentRegisterVO aiAgentRegisterVO = defaultArmoryFactory.getAiAgentRegisterVO(agentId);

        if (null == aiAgentRegisterVO) {
            throw new AppException(ResponseCode.E0001.getCode());
        }

        String appName = aiAgentRegisterVO.getAppName();
        InMemoryRunner runner = aiAgentRegisterVO.getRunner();

        return userSessions.computeIfAbsent(agentId + "\u0000" + userId, key -> {
            Session session = runner.sessionService().createSession(appName, userId)
                    .blockingGet();
            return session.id();
        });
    }

    /** 按指定智能体与用户会话处理消息，返回回复内容。 */
    public List<String> handleMessage(String agentId, String userId, String message) {

        AiAgentRegisterVO aiAgentRegisterVO = defaultArmoryFactory.getAiAgentRegisterVO(agentId);

        if (null == aiAgentRegisterVO) {
            throw new AppException(ResponseCode.E0001.getCode());
        }

        String sessionId = createSession(agentId, userId);

        return handleMessage(agentId, userId, sessionId, message);
    }

    /** 按指定智能体与用户会话处理消息，返回回复内容。 */
    public List<String> handleMessage(String agentId, String userId, String sessionId, String message) {

        AiAgentRegisterVO aiAgentRegisterVO = defaultArmoryFactory.getAiAgentRegisterVO(agentId);

        if (null == aiAgentRegisterVO) {
            throw new AppException(ResponseCode.E0001.getCode());
        }

        InMemoryRunner runner = aiAgentRegisterVO.getRunner();

        Content userMsg = Content.fromParts(Part.fromText(message));
        Flowable<Event> events = runner.runAsync(userId, sessionId, userMsg);

        List<String> outputs = new ArrayList<>();
        events.blockingForEach(event -> outputs.add(event.stringifyContent()));

        return outputs;
    }

    /** 调用智能体运行器并输出原始运行事件。 */
    public Flowable<Event> adkEvents(String agentId, String userId, String sessionId, String message) {
        AiAgentRegisterVO aiAgentRegisterVO = defaultArmoryFactory.getAiAgentRegisterVO(agentId);

        if (null == aiAgentRegisterVO) {
            throw new AppException(ResponseCode.E0001.getCode());
        }

        InMemoryRunner runner = aiAgentRegisterVO.getRunner();

        Content userMsg = Content.fromParts(Part.fromText(message));
        // ADK 默认 NONE；只有显式 SSE 才会让模型适配器请求真实增量。
        return runner.runAsync(userId, sessionId, userMsg,
                RunConfig.builder().setStreamingMode(RunConfig.StreamingMode.SSE).build());
    }

    /** 流式处理会话消息，逐项返回智能体事件。 */
    public Flux<String> handleMessageStream(String agentId, String userId, String sessionId, String message) {
        return Flux.defer(() -> Flux.from(adkEvents(agentId, userId, sessionId, message))).map(Event::stringifyContent);
    }

    /** 使用已组装的上下文生成文本回复，不再重复拼接历史。 */
    public Flux<String> handlePreparedTextStream(String agentId, String userId, String message, String policy,
            cn.adinall.ai.domain.companion.model.valobj.AgentInvocationVO invocation) {
        return Flux.defer(() -> {
            String key = cn.adinall.ai.infrastructure.gateway.agent.tool.AgentToolContextRegistry.register(invocation);
            return handlePreparedTextStream(agentId, userId, message, policy + "\nSERVER_TOOL_CONTEXT=" + key)
                .doFinally(signal -> cn.adinall.ai.infrastructure.gateway.agent.tool.AgentToolContextRegistry.release(key));
        });
    }

    /** 将模型事件转为纯文本；defer 保证装配失败在订阅错误通道中传播。 */
    public Flux<String> handleTextStream(String agentId, String userId, String sessionId, String message) {
        return AgentTextStream.from(Flux.defer(() ->
                Flux.from(adkEvents(agentId, userId, sessionId, message))));
    }

    /** 使用已组装的上下文生成文本回复，不再重复拼接历史。 */
    public Flux<String> handlePreparedTextStream(String agentId, String userId, String preparedMessage) {
        return handlePreparedTextStream(agentId, userId, preparedMessage, "");
    }

    /** 使用已组装的上下文生成文本回复，不再重复拼接历史。 */
    public Flux<String> handlePreparedTextStream(String agentId, String userId, String preparedMessage, String systemPolicy) {
        return Flux.defer(() -> {
            AiAgentRegisterVO register = defaultArmoryFactory.getAiAgentRegisterVO(agentId);
            if (register == null) return Flux.error(new AppException(ResponseCode.E0001.getCode()));
            InMemoryRunner runner = register.getRunner();
            // 每轮新建短生命周期会话：历史由 providers 重建进 preparedMessage，不靠 ADK 累积；
            // spoken-server.yml 的 {turn_policy?} 由 ADK 从本轮会话 state 注入 SYSTEM，
            // 不修改共享 Agent，避免并发学员的等级与任务串轮。
            Mono<Session> resource = Mono.defer(() -> {
                ConcurrentHashMap<String, Object> state = new java.util.concurrent.ConcurrentHashMap<String, Object>();
                state.put("turn_policy", systemPolicy == null ? "" : systemPolicy);
                return Mono.from(runner.sessionService().createSession(register.getAppName(), userId, state, null).toFlowable());
            });
            java.util.function.Function<Session, Mono<Void>> release = s -> Mono.from(runner.sessionService().deleteSession(register.getAppName(),userId,s.id()).toFlowable()).then();
            return Flux.usingWhen(resource, s -> handleTextStream(agentId,userId,s.id(),preparedMessage),
                    release, (s,error) -> release.apply(s), release);
        });
    }

    /** 关闭指定会话并释放对应运行资源。 */
    public void closeSession(String agentId, String userId) {
        String id=userSessions.remove(agentId+"\u0000"+userId);
        if(id==null) return;
        AiAgentRegisterVO register=defaultArmoryFactory.getAiAgentRegisterVO(agentId);
        if(register!=null) register.getRunner().sessionService().deleteSession(register.getAppName(),userId,id).blockingAwait();
    }

    /** 按指定智能体与用户会话处理消息，返回回复内容。 */
    public List<String> handleMessage(ChatCommandEntity chatCommandEntity) {
        AiAgentRegisterVO aiAgentRegisterVO = defaultArmoryFactory.getAiAgentRegisterVO(chatCommandEntity.getAgentId());

        if (null == aiAgentRegisterVO) {
            throw new AppException(ResponseCode.E0001.getCode());
        }

        List<Part> parts = new ArrayList<>();

        List<ChatCommandEntity.Content.Text> texts = chatCommandEntity.getTexts();
        if (null != texts && !texts.isEmpty()) {
            for (ChatCommandEntity.Content.Text text : texts) {
                parts.add(Part.fromText(text.getMessage()));
            }
        }

        List<ChatCommandEntity.Content.File> files = chatCommandEntity.getFiles();
        if (null != files && !files.isEmpty()) {
            for (ChatCommandEntity.Content.File file : files) {
                parts.add(Part.fromUri(file.getFileUri(), file.getMimeType()));
            }
        }

        List<ChatCommandEntity.Content.InlineData> inlineDatas = chatCommandEntity.getInlineDatas();
        if (null != inlineDatas && !inlineDatas.isEmpty()) {
            for (ChatCommandEntity.Content.InlineData inlineData : inlineDatas) {
                parts.add(Part.fromBytes(inlineData.getBytes(), inlineData.getMimeType()));
            }
        }

        Content content = Content.builder().role("user").parts(parts).build();

        // 获取运行体
        InMemoryRunner runner = aiAgentRegisterVO.getRunner();

        Flowable<Event> events = runner.runAsync(chatCommandEntity.getUserId(), chatCommandEntity.getSessionId(), content);

        List<String> outputs = new ArrayList<>();
        events.blockingForEach(event -> outputs.add(event.stringifyContent()));

        return outputs;
    }

}

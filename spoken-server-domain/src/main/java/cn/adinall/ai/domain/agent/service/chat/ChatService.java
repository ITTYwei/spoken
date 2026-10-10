package cn.adinall.ai.domain.agent.service.chat;

import cn.adinall.ai.domain.agent.model.entity.ChatCommandEntity;
import cn.adinall.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import cn.adinall.ai.domain.agent.model.valobj.AiAgentRegisterVO;
import cn.adinall.ai.domain.agent.model.valobj.properties.AiAgentAutoConfigProperties;
import cn.adinall.ai.domain.agent.service.IChatService;
import cn.adinall.ai.domain.agent.service.armory.factory.DefaultArmoryFactory;
import cn.adinall.ai.types.enums.ResponseCode;
import cn.adinall.ai.types.exception.AppException;
import com.google.adk.events.Event;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 对话领域服务（agent 能力的运行期实现：装配产物 -> 真实对话）
 * <p>
 * 功能：运行期唯一直接使用装配产物的地方 —— 按 agentId 取回 Runner，建/复用会话，
 * 把入参拼成 ADK 的 Content 后发起对话，返回模型输出。
 * <pre>
 *   queryAiAgentConfigList()                          从配置属性里列出全部已配置智能体
 *   createSession(agentId, userId)                    取 Runner -> ADK sessionService.createSession(appName, userId)
 *   handleMessage(agentId, userId, message)           (createSession) + 单轮对话
 *   handleMessage(agentId, userId, sessionId, message) 指定会话的单轮对话（同步收集全部事件）
 *   handleMessageStream(...)                          指定会话的流式对话，直接返回 Flowable&lt;Event&gt;
 *   handleMessage(ChatCommandEntity)                  复合内容（文本/文件/内联）发消息
 * </pre>
 * 运行过程（一次同步对话）：
 * <pre>
 *   AgentServiceController / 上层
 *        |
 *        v
 *   ChatService.handleMessage(agentId, userId, sessionId, message)
 *        |
 *        | (1) defaultArmoryFactory.getAiAgentRegisterVO(agentId)
 *        |        |  取不到（未装配/agentId 写错）--> 抛 AppException(ResponseCode.E0001)
 *        |        v
 *        |     AiAgentRegisterVO{appName, runner, ...}
 *        |
 *        | (2) Content.fromParts(Part.fromText(message))
 *        |
 *        | (3) runner.runAsync(userId, sessionId, userMsg) --> Flowable&lt;Event&gt;
 *        |        |
 *        |        v
 *        |     blockingForEach: event.stringifyContent() 逐个收进 List&lt;String&gt;（此处会阻塞）
 *        v
 *   List&lt;String&gt; 返回给调用方；副作用：ADK 会话内多了一轮 user/assistant 事件
 * </pre>
 * 案例：
 * <pre>
 *   输入: agentId="3001", userId="user-001", sessionId="sess-abc", message="你好"
 *   处理: getBean("3001") 取到 Runner --> Content(role=user, text="你好") --> runAsync --> blockingForEach
 *   输出: ["你好！我是口语陪练，我们从自我介绍开始吧。"]
 *         另一例: createSession("3001","user-001") 首次调用返回 "sess-abc" 并缓存；
 *                 同一 userId 再调直接返回 "sess-abc"（不会新建）
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>会话按 userId 缓存（userSessions: ConcurrentHashMap）：同一用户的多次发消息复用同一个 ADK 会话，
 *       多轮上下文才连得上；代价是同一 userId 换 agentId 时也会复用它第一次建的那个会话。</li>
 *   <li>取不到装配产物直接抛 E0001：运行期不做「顺手装配」，保证对话路径只有一种失败语义（智能体不存在）。</li>
 *   <li>同步方法内部 blockingForEach 会阻塞调用线程：批量/耗时的对话应走
 *       handleMessageStream 返回的 Flowable，由 WebFlux/SSE 侧消费。</li>
 *   <li>复合入参那条按 texts -> files -> inlineDatas 顺序拼 Part：顺序决定模型看到的内容次序，
 *       文本在前更利于模型先理解意图再读附件。</li>
 *   <li>本类不认识 ADK 之外的任何东西：换模型/换智能体结构都在装配期完成，这里只做「取 Runner + 发消息」。</li>
 * </ul>
 */
@Slf4j
@Service
public class ChatService implements IChatService {

    @Resource
    private DefaultArmoryFactory defaultArmoryFactory;

    @Resource
    private AiAgentAutoConfigProperties aiAgentAutoConfigProperties;

    private final Map<String, String> userSessions = new ConcurrentHashMap<>();

    @Override
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

    @Override
    public String createSession(String agentId, String userId) {
        AiAgentRegisterVO aiAgentRegisterVO = defaultArmoryFactory.getAiAgentRegisterVO(agentId);

        if (null == aiAgentRegisterVO) {
            throw new AppException(ResponseCode.E0001.getCode());
        }

        String appName = aiAgentRegisterVO.getAppName();
        InMemoryRunner runner = aiAgentRegisterVO.getRunner();

        return userSessions.computeIfAbsent(userId, uid -> {
            Session session = runner.sessionService().createSession(appName, uid)
                    .blockingGet();
            return session.id();
        });
    }

    @Override
    public List<String> handleMessage(String agentId, String userId, String message) {

        AiAgentRegisterVO aiAgentRegisterVO = defaultArmoryFactory.getAiAgentRegisterVO(agentId);

        if (null == aiAgentRegisterVO) {
            throw new AppException(ResponseCode.E0001.getCode());
        }

        String sessionId = createSession(agentId, userId);

        return handleMessage(agentId, userId, sessionId, message);
    }

    @Override
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

    @Override
    public Flowable<Event> handleMessageStream(String agentId, String userId, String sessionId, String message) {
        AiAgentRegisterVO aiAgentRegisterVO = defaultArmoryFactory.getAiAgentRegisterVO(agentId);

        if (null == aiAgentRegisterVO) {
            throw new AppException(ResponseCode.E0001.getCode());
        }

        InMemoryRunner runner = aiAgentRegisterVO.getRunner();

        Content userMsg = Content.fromParts(Part.fromText(message));
        return runner.runAsync(userId, sessionId, userMsg);
    }

    @Override
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

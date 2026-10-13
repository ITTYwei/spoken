package cn.adinall.ai.domain.agent.service;

import cn.adinall.ai.domain.agent.model.entity.ChatCommandEntity;
import cn.adinall.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import com.google.adk.events.Event;
import io.reactivex.rxjava3.core.Flowable;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 对话服务接口（agent 能力的对外端口）
 * <p>
 * 功能：声明「列出可用智能体 / 建会话 / 发消息（同步与流式）」这四类能力，
 * 实现是 ChatService；调用方（Controller / case）只依赖本接口。
 * <pre>
 *   queryAiAgentConfigList()                             列出配置里的全部智能体
 *   createSession(agentId, userId)                       按 userId 建/复用 ADK 会话，返回 sessionId
 *   handleMessage(agentId, userId, message)              建会话后发消息，返回多段文本
 *   handleMessage(agentId, userId, sessionId, message)    在指定会话里发消息
 *   handleMessageStream(agentId, userId, sessionId, msg)  流式发消息，返回 Flowable&lt;Event&gt;
 *   handleTextStream(agentId, userId, sessionId, msg)     纯文本流，Agent 内过滤回显与重复汇总
 *   handleMessage(ChatCommandEntity)                      复合入参：文本 + 文件 + 内联数据
 * </pre>
 * 体系架构（调用方 -> 接口 -> 实现 -> 装配产物）：
 * <pre>
 *   AgentServiceController（HTTP） / ConversationCaseImpl（用例）
 *        |
 *        v
 *   IChatService   <-- 本接口
 *        |
 *        v
 *   ChatService
 *        |  defaultArmoryFactory.getAiAgentRegisterVO(agentId)
 *        v
 *   AiAgentRegisterVO.runner (InMemoryRunner)
 *        |  runner.sessionService().createSession(appName, userId)
 *        |  runner.runAsync(userId, sessionId, Content.fromParts(...))
 *        v
 *   List&lt;String&gt; / Flowable&lt;Event&gt; / Flux&lt;String&gt;
 * </pre>
 * 案例：
 * <pre>
 *   输入: handleMessage("3001", "user-001", "你好，帮我练口语")
 *   处理: 取 bean "3001" --> 建/复用会话 --> runner.runAsync 同步收集全部事件
 *   输出: ["你好！我们从自我介绍开始吧。"]（每个元素是一段智能体输出）
 *         异常: agentId 未装配时实现抛 AppException(E0001)
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>接口只按「agentId + userId (+ sessionId)」收参：调用方不需要知道 ADK 的 runner/session 概念，
 *       换实现（例如换成远程 Runner）不影响 Controller。</li>
 *   <li>同时提供同步与流式两个入口：同步便于一次性拿完整回复，流式（Flowable&lt;Event&gt;）
 *       供 SSE 逐段推送，避免长回答把请求挂住。</li>
 *   <li>多一个 ChatCommandEntity 重载：文本之外的图片/文件/内联数据要按 Part 拼进同一条消息，
 *       这是「多模态入参」的入口，其余重载只支持纯文本。</li>
 * </ul>
 *
 * @author 魏玉石
 * 2025/12/17 08:13
 */
public interface IChatService {

    /**
     * 列出配置里声明过的全部智能体（只取配置表里的 agent 身份段，不含 module 装配细节）。
     *
     * 流程：读 AiAgentAutoConfigProperties.getTables()，逐个取 table.getAgent() 拼成列表；
     * 纯内存读，不碰装配产物、也不校验这些 agent 是否真的装配成功。
     * 注意：返回的是「配置里有什么」，不是「运行期能用什么」——配置了但装配失败时应用根本起不来。
     *
     * @return 智能体身份列表（agentId / agentName / agentDesc）；未配置任何智能体时返回空列表
     */
    List<AiAgentConfigTableVO.Agent> queryAiAgentConfigList();

    /**
     * 为用户建（或复用）一个智能体会话，返回会话 id。
     *
     * 流程：按 agentId 取装配产物 AiAgentRegisterVO -> 取不到抛 AppException(E0001) ->
     * 以 userId 为键查本地缓存，没有就用 runner.sessionService().createSession(appName, userId) 新建
     * （blockingGet，建会话失败会直接抛异常）-> 缓存并返回 session.id()。
     *
     * 并发安全：缓存是 ConcurrentHashMap + computeIfAbsent，同一 userId 并发首次调用只会建一个会话；
     * fetch 抛异常时不会写入缓存。
     *
     * @param agentId 装配期注册进容器的智能体 id（即配置里的 agent.agent-id），不是配置表的 key
     * @param userId  调用方自己的用户标识，同时是 ADK 侧会话归属的标识
     * @return ADK 会话 id（后续 handleMessage / handleMessageStream 用它续上下文）
     * @throws cn.adinall.ai.types.exception.AppException agentId 未装配时抛 E0001（args 只带 code）
     */
    String createSession(String agentId, String userId);

    /**
     * 发一条消息并**同步等完整回复**（不指定会话：内部先建/复用会话）。
     *
     * 流程：createSession(agentId, userId) 拿到 sessionId -> 委托给
     * {@link #handleMessage(String, String, String, String)} 完成整轮对话。
     * 注意：本方法不接受 sessionId，复用哪个会话完全由 createSession 的 userId 缓存决定。
     *
     * @param agentId 智能体 id
     * @param userId  用户标识（决定复用哪个会话）
     * @param message 用户输入的纯文本
     * @return 智能体输出分段（每段一个字符串，由 adk Event.stringifyContent() 得出）；无输出时为空列表
     * @throws cn.adinall.ai.types.exception.AppException agentId 未装配时抛 E0001
     */
    List<String> handleMessage(String agentId, String userId, String message);

    /**
     * 在指定会话里发一条消息并**同步等完整回复**。
     *
     * 流程：agentId 取装配产物（取不到抛 E0001）-> 把 message 包成 ADK 的
     * Content.fromParts(Part.fromText(message))，role=user -> runner.runAsync(userId, sessionId, content)
     * -> blockingForEach 收齐全部事件，取 event.stringifyContent() 装进列表返回。
     *
     * ⚠️ 这是**阻塞**实现：一直等到这一轮跑完（含工具调用往返）才返回，调用线程会被占住。
     * 回复内容长或要走 SSE 时请改用 {@link #handleMessageStream}。
     *
     * @param agentId   智能体 id
     * @param userId    用户标识（与建会话时一致，ADK 用它校验会话归属）
     * @param sessionId 目标会话 id；传不存在的 id 时由 ADK 侧报错，本接口不做存在性校验
     * @param message   用户输入的纯文本
     * @return 智能体输出分段；无输出时为空列表
     * @throws cn.adinall.ai.types.exception.AppException agentId 未装配时抛 E0001
     */
    List<String> handleMessage(String agentId, String userId, String sessionId, String message);

    /**
     * 在指定会话里发一条消息并**流式返回事件**（本接口对流式的唯一入口）。
     *
     * 流程：agentId 取装配产物（取不到抛 E0001）-> 包 Content -> 直接返回
     * runner.runAsync(userId, sessionId, content) 这条 Flowable&lt;Event&gt; —— 本方法**不订阅、不阻塞**，
     * 订阅与逐条下发由调用方决定（AgentServiceController 用 ResponseBodyEmitter 逐条 send，
     * SSE 场景由用例层订阅）。
     *
     * @param agentId   智能体 id
     * @param userId    用户标识
     * @param sessionId 目标会话 id（流式场景必须已有会话，本方法不会替调用方建会话）
     * @param message   用户输入的纯文本
     * @return 事件流 Flowable&lt;Event&gt;；由调用方 subscribe 时才真正发起请求，中途断开即取消订阅
     * @throws cn.adinall.ai.types.exception.AppException agentId 未装配时抛 E0001（在方法调用阶段就抛，
     *         不会伪装成流里的错误信号）
     */
    Flowable<Event> handleMessageStream(String agentId, String userId, String sessionId, String message);

    /**
     * 复合入参发消息（**多模态入口**：文本 + 文件引用 + 内联二进制），同步等完整回复。
     *
     * 流程：按 agentId 取装配产物（取不到抛 E0001）-> 按 texts、files、inlineDatas 三个列表的顺序
     * 依次拼成 List&lt;Part&gt;（Part.fromText / Part.fromUri / Part.fromBytes）-> 合成一条 role="user" 的
     * Content -> runner.runAsync(...) 同步收齐事件并返回文本分段。三个列表都允许为 null 或空。
     *
     * ⚠️ 同样是阻塞实现（blockingForEach），与 {@link #handleMessage(String, String, String, String)} 一致。
     *
     * @param chatCommandEntity 命令实体：agentId / userId / sessionId 必填，texts、files、inlineDatas 至少给一个
     * @return 智能体输出分段；无输出时为空列表
     * @throws cn.adinall.ai.types.exception.AppException agentId 未装配时抛 E0001
     */
    List<String> handleMessage(ChatCommandEntity chatCommandEntity);

    /**
     * 生成纯文本增量。ADK 回显过滤、错误传播和汇总去重由 Agent 领域完成。
     * <p>调用方先通过 createSession 建立上下文，再订阅本方法返回的流。
     * @return 纯文本增量；每次订阅独立转换，保留原始事件流接口用于工具事件展示
     */
    Flux<String> handleTextStream(String agentId, String userId, String sessionId, String message);

    /**
     * 在短生命周期 ADK 会话中消费已裁剪上下文；结束、失败和取消均释放会话。
     * 业务历史由 context provider 提供，ADK 不再隐式追加旧控制提示词。
     */
    Flux<String> handlePreparedTextStream(String agentId, String userId, String preparedMessage);

    /**
     * 在短会话中分别传入 USER 上下文与可信的 SYSTEM 本轮规则。
     * systemPolicy 只能由服务端策略生成；用户原话、人设和记忆必须留在 preparedMessage。
     */
    Flux<String> handlePreparedTextStream(String agentId, String userId, String preparedMessage, String systemPolicy);

    /** 关闭通用对话接口的缓存会话，不能跨 agentId 删除其他会话。 */
    void closeSession(String agentId, String userId);

}

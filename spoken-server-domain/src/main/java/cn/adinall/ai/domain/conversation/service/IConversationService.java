package cn.adinall.ai.domain.conversation.service;

import cn.adinall.ai.domain.conversation.model.aggregate.ConversationSessionAggregate;
import cn.adinall.ai.domain.conversation.model.aggregate.ConversationTurnAggregate;
import cn.adinall.ai.domain.conversation.model.entity.SayCommandEntity;
import cn.adinall.ai.domain.conversation.model.entity.SpeakCommandEntity;
import cn.adinall.ai.domain.conversation.model.entity.StartConversationCommandEntity;
import cn.adinall.ai.domain.conversation.model.valobj.ConversationEventVO;
import cn.adinall.ai.domain.conversation.model.valobj.IceServerVO;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 会话领域服务接口（「会话状态机」的唯一入口）
 * <p>
 * 功能：所有会改会话状态的动作都必须从这里过；编排层和触发层只能请求，不能绕过。
 * <pre>
 *   createSession(StartConversationCommandEntity)  建会话，不在里面等上游就绪
 *   requireSession(String)                         取会话，取不到抛 session_not_found
 *   refreshStatus(ConversationSessionAggregate)    向上游纠偏一次状态
 *   closeSessionsOfDevice(String)                  关掉某设备当前的会话
 *   iceServers()                                   取 WebRTC ICE 配置
 *   exchangeOffer(String, String)                  SDP 交换；排队中抛 session_queued
 *   speak(String, SpeakCommandEntity)              文本驱动（走 LLM 生成回复）
 *   say(String, SayCommandEntity)                  脚本轨直念（不走 LLM）
 *   interrupt(String)                              打断 + 补一条本地 interrupted 事件
 *   transcribeAndRecord(String, byte[], int)       语音上行识别，返回识别文本
 *   closeSession(String)                           结束会话，重复调用安全
 *   applyDriverEvent(聚合, 事件)                   处理驱动事件，返回该发给客户端的那条
 *   streamDriverEvents(聚合)                       驱动事件流（已过 applyDriverEvent）
 *   subscribeLocalEvents(String)                   本地事件流（stt.final / interrupted）
 *   listTurns(String)                              本场会话的全部回合（按 seq 升序）
 * </pre>
 * 体系架构（谁调它、它调谁）：
 * <pre>
 *   ConversationCaseImpl（用例层：负责等与拼装，sleep 只在这一层）
 *        |
 *        v
 *   IConversationService（本接口，只用领域类型）
 *        |                              \
 *        | 端口                          \ 仓储
 *        v                                v
 *   IAvatarDriverPort                 IConversationSessionRepository
 *   IConversationEventBusPort         IConversationTurnRepository
 *        |
 *        v
 *   OpenTalkingAvatarDriver 等适配器（基础设施层，翻译上游 JSON / SSE）
 * </pre>
 * 案例：
 * <pre>
 *   输入: say("s-9c1d2f3e", new SayCommandEntity("Hi! I'm Amy.", null, null))
 *   处理: requireSession("s-9c1d2f3e") 拿到 providerSessionId="ot-7f3a"
 *             （查不到就抛 session_not_found --> 404）
 *         command.voice() 为空 --> 补上 session.getVoice()="en-US-AriaNeural"
 *         avatarDriver.say("ot-7f3a", command)
 *   输出: 无返回值；说话进度由事件流给出（assistant.delta 增量、assistant.done 整段）
 *   对照: 同样一句换成 speak(...)，text 会被上游当「用户说的话」，由 LLM 生成回复再念（秒级）
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>状态怎么变、什么情况下不允许操作，全收在聚合 + 这一个服务里：
 *       一旦允许编排层自己改状态，同一个状态就有两条修改路径，日志也说不清会话死在谁手上。</li>
 *   <li>刻意不做 sleep / 轮询：「等上游就绪」「等 GPU 槽位释放」属于编排决策，
 *       放在用例层（ConversationCaseImpl.awaitReady 每 400ms 问一次，预算 readyWaitMs）。</li>
 *   <li>接口只认识端口和仓储，不认识 HTTP / JSON / 数据库；换数字人后端时本接口一行不改。</li>
 *   <li>实现侧的现状比早期注释更宽：refreshStatus 对「未收尾」的会话一律纠偏
 *       （QUEUED / READY / ACTIVE 都问），只有 CLOSING / CLOSED / ERROR 才跳过 ——
 *       否则上游偷偷关掉的会话会一直停在本地 READY，App 只会拿一个废号反复换 SDP。</li>
 * </ul>
 */
public interface IConversationService {

    /**
     * 建一场会话：调驱动端口拿句柄，组装聚合并登记（内存运行态 + 会话档案）。
     * **不会**在这里等上游就绪 —— 等待策略由用例层决定，超时了怎么办也是它的事。
     */
    ConversationSessionAggregate createSession(StartConversationCommandEntity command);

    /** 按会话 id 取会话；取不到直接抛 session_not_found（404）。 */
    ConversationSessionAggregate requireSession(String sessionId);

    /**
     * 向上游纠偏一次会话状态（只在本地还是 QUEUED 时才问）。
     * 建会话后的轮询等待、以及 App 轮询会话状态都会用到。
     * @return 传入的聚合（状态可能已被更新）
     */
    ConversationSessionAggregate refreshStatus(ConversationSessionAggregate session);

    /**
     * 关掉某个设备当前的会话（含落库与摘除运行态）。
     *
     * @return 是否真的关掉了一场会话。调用方拿它决定「要不要等槽位释放」——
     *         没关掉旧会话时上游槽位本来就是空的，多等纯属浪费。
     */
    boolean closeSessionsOfDevice(String deviceId);

    /** 取 WebRTC 的 ICE 服务器配置。 */
    List<IceServerVO> iceServers();

    /** WebRTC SDP 交换，返回 answer。排队中时抛 session_queued（409）。 */
    String exchangeOffer(String sessionId, String offerSdp);

    /** 文本驱动数字人说话。只下发，不在这里等它说完。 */
    void speak(String sessionId, SpeakCommandEntity command);

    /**
     * 脚本轨：让数字人**原样念出**这句话，不经过 LLM。
     * 增量（assistant.delta）与收口（assistant.done）同样从事件流出来。
     */
    void say(String sessionId, SayCommandEntity command);

    /** 打断数字人当前正在说的话，并往本地事件流里补一条打断回执。 */
    void interrupt(String sessionId);

    /**
     * 上行一段 PCM：让驱动后端做服务端识别，识别文本直接落成一条用户回合，
     * 并通过本地事件总线推一条 stt.final 出去。
     *
     * 注意：上游拿到文本后会**自己**触发数字人说话，所以这里绝对不能再调一次 speak，
     * 否则同一句话会被读两遍。
     *
     * @return 识别出的文本
     */
    String transcribeAndRecord(String sessionId, byte[] pcm, int sampleRate);

    /** 结束会话并释放上游资源；重复调用安全。 */
    void closeSession(String sessionId);

    /**
     * 处理一条来自驱动后端的事件：推进会话状态、落库回合，
     * 并返回真正该发给客户端的那条事件（允许在这里把上游 payload 规整成我们承诺的形状）。
     */
    ConversationEventVO applyDriverEvent(ConversationSessionAggregate session, ConversationEventVO event);

    /**
     * 订阅驱动后端的事件流，每条都已过 {@link #applyDriverEvent}（状态已推进、回合已落库），
     * 拿到的就是「可以直接发给客户端」的事件。适配器不认识业务，所以这层包装留在这里。
     */
    Flux<ConversationEventVO> streamDriverEvents(ConversationSessionAggregate session);

    /**
     * 订阅本场会话的本地事件流（不走驱动后端的那几条：stt.final、interrupted）。
     * 这些事件在产生时就已经是最终形状、也已经落过库，所以不再过 {@link #applyDriverEvent}。
     */
    Flux<ConversationEventVO> subscribeLocalEvents(String sessionId);

    /** 取本场会话的全部回合（按 seq 升序）。 */
    List<ConversationTurnAggregate> listTurns(String sessionId);
}
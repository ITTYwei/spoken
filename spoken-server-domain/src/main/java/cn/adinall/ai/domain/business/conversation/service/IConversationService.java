package cn.adinall.ai.domain.business.conversation.service;

import cn.adinall.ai.domain.business.conversation.model.aggregate.ConversationSessionAggregate;
import cn.adinall.ai.domain.business.conversation.model.aggregate.ConversationTurnAggregate;
import cn.adinall.ai.domain.business.conversation.model.entity.SayCommandEntity;
import cn.adinall.ai.domain.business.conversation.model.entity.SpeakCommandEntity;
import cn.adinall.ai.domain.business.conversation.model.entity.StartConversationCommandEntity;
import cn.adinall.ai.domain.business.conversation.model.valobj.ConversationEventVO;
import cn.adinall.ai.domain.business.conversation.model.valobj.IceServerVO;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 会话领域服务。
 *
 * 它是「会话状态机」的唯一入口：所有会改会话状态的动作都从这里过，
 * 好处是状态该怎么变、什么情况下不允许操作，全都收在聚合 + 这一个服务里，
 * 编排层和触发层只能请求，不能绕过。
 *
 * 它不认识 HTTP、不认识 JSON、不认识数据库 —— 只认识端口（驱动 / 事件总线）
 * 和仓储（会话 / 回合）。
 *
 * 刻意**不做**的事：不 sleep、不轮询。等上游就绪、等 GPU 槽位释放都属于「等到什么时候算了」
 * 的编排决策，放在用例层（case），领域服务只管「问一次上游状态是多少」。
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
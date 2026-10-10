package cn.adinall.ai.infrastructure.adapter.port;

import cn.adinall.ai.domain.conversation.adapter.port.IConversationEventBusPort;
import cn.adinall.ai.domain.conversation.model.valobj.ConversationEventVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内会话事件总线（IConversationEventBusPort 的本地实现：每场会话一个 Reactor Sinks）
 * <p>
 * 功能：把我们自己产生的事件（不是上游给的）汇进该会话的事件流，与上游事件共用同一条 SSE。
 * <pre>
 *   publish(sessionId, event)    往该会话的 sink 里塞一条事件；没有订阅者时静默丢弃
 *   subscribe(sessionId)         订阅该会话的事件流（热流，订阅之后产生的事件才收得到）
 *   remove(sessionId)            会话结束时摘掉通道，避免 sinksBySession 越积越多
 * </pre>
 * 运行过程（一次语音上行识别出的用户台词如何到达 App）：
 * <pre>
 *   ConversationServiceImpl.transcribeAndRecord()
 *        |
 *        | (1) text = avatarDriver.transcribeAudio(...)
 *        |     turnId = appendUserTurn(sessionId, text).getId()
 *        |
 *        | (2) eventBus.publish(sessionId,
 *        |         ConversationEventVO.of(STT_FINAL, Map.of("text", text, "turnId", turnId)))
 *        v
 *   sink(sessionId)
 *        |
 *        +--> sinksBySession.computeIfAbsent(sessionId,
 *        |         key -> Sinks.many().multicast().directBestEffort())
 *        v
 *   tryEmitNext(event) --> EmitResult（无订阅者时 FAIL_ZERO_SUBSCRIBER，只记 debug）
 *        |
 *        v
 *   ConversationCaseImpl.events(): Flux.concat(head, Flux.merge(body, local, heartbeat))
 *        |
 *        v
 *   App 的 SSE 上多出一条 stt.final
 * </pre>
 * 案例：
 * <pre>
 *   输入: sessionId = "s-3f9c1a77e2b04d15",
 *         event = ConversationEventVO.of(STT_FINAL, Map.of("text", "I want a coffee", "turnId", 42L))
 *   处理: computeIfAbsent 取/建该会话的 multicast.directBestEffort sink -> tryEmitNext
 *   输出: 无返回值；副作用是该会话的本地订阅者流里多一条 STT_FINAL；
 *         若 App 还没连上 SSE，emit 结果是 FAIL_ZERO_SUBSCRIBER，事件被丢弃且只记 debug
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>用 directBestEffort 而不是带缓冲的 multicast：这类是即时事件，没有订阅者时丢掉才是对的；
 *       缓冲下来等下一个 SSE 连接再补发，重连的 App 会收到一句早就过期的用户台词。</li>
 *   <li>remove 只摘通道、不 complete：已经连上的 SSE 由上游事件流与心跳继续维持，
 *       提前 complete 会让 App 莫名其妙断流。</li>
 *   <li>sinksBySession 用 ConcurrentHashMap + computeIfAbsent：上游事件线程与 HTTP 请求线程
 *       会并发碰同一场会话，先 hasKey 再 put 的写法会出现两个 sink、事件丢一半。</li>
 *   <li>扩展点：换分布式部署（多实例）时把这个实现换成走 Redis/MQ 的适配器即可，
 *       领域服务只认 IConversationEventBusPort，一行都不用改。</li>
 * </ul>
 */
@Component
public class InProcessConversationEventBus implements IConversationEventBusPort {

    private static final Logger log = LoggerFactory.getLogger(InProcessConversationEventBus.class);

    private final Map<String, Sinks.Many<ConversationEventVO>> sinksBySession = new ConcurrentHashMap<>();

    /**
     * 往某场会话的流里塞一条本地事件。
     * 流程：取（或建）该会话的 sink -> tryEmitNext；没有订阅者时返回 FAIL_ZERO_SUBSCRIBER，
     * 属正常情况（App 没在听 SSE），只记 debug。
     */
    @Override
    public void publish(String sessionId, ConversationEventVO event) {
        Sinks.EmitResult result = sink(sessionId).tryEmitNext(event);
        log.debug("本地事件 session={} type={} emit={}", sessionId, event.type().wire(), result);
    }

    /**
     * 订阅某场会话的本地事件流（热流）：只有订阅之后产生的事件收得到，这是有意的（见类注释）。
     */
    @Override
    public Flux<ConversationEventVO> subscribe(String sessionId) {
        return sink(sessionId).asFlux();
    }

    /**
     * 会话结束时摘掉通道，避免 Sinks 越积越多。
     * 注意**不** complete：已经连上的 SSE 由上游事件流与心跳继续维持，
     * 提前 complete 会让 App 那边莫名其妙断流。
     */
    @Override
    public void remove(String sessionId) {
        sinksBySession.remove(sessionId);
    }

    /** 取该会话的 sink，没有就建一个。 */
    private Sinks.Many<ConversationEventVO> sink(String sessionId) {
        return sinksBySession.computeIfAbsent(sessionId,
                key -> Sinks.many().multicast().directBestEffort());
    }
}
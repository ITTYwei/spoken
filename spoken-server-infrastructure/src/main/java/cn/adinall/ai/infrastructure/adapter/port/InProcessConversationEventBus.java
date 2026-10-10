package cn.adinall.ai.infrastructure.adapter.port;

import cn.adinall.ai.domain.business.conversation.adapter.port.IConversationEventBusPort;
import cn.adinall.ai.domain.business.conversation.model.valobj.ConversationEventVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内的会话事件总线：每场会话一个 Sinks，把「我们自己产生的事件」汇进同一条 SSE。
 *
 * 为什么用 directBestEffort 而不是带缓冲的 multicast：这类是即时事件，
 * 没有订阅者时直接丢掉才是对的；缓冲下来等下一个 SSE 连接再补发，
 * 重连的 App 会收到一句早就过期的用户台词。
 *
 * 换分布式部署（多实例）时，这个实现要换成走 Redis/MQ 的适配器 —— 领域服务不用改。
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
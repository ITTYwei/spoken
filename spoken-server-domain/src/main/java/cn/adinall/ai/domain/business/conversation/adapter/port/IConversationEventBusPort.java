package cn.adinall.ai.domain.business.conversation.adapter.port;

import cn.adinall.ai.domain.business.conversation.model.valobj.ConversationEventVO;
import reactor.core.publisher.Flux;

/**
 * 会话本地事件总线端口。
 *
 * 为什么需要一个「本地」总线：有些事件不是上游给的，而是我们自己产生的 ——
 * 比如语音上行识别出的用户台词（stt.final），上游那条流里根本没有；
 * 再比如打断回执，上游只回 HTTP 200、不发任何事件。
 *
 * 这些事件必须和上游事件汇进同一条 SSE 送给 App，否则客户端要同时听两个来源。
 * 「怎么把它们汇起来」是基础设施的事（进程内 Sinks），领域只声明这个能力。
 */
public interface IConversationEventBusPort {

    /** 往某场会话的流里塞一条本地事件。没有订阅者时静默丢弃，不算错误。 */
    void publish(String sessionId, ConversationEventVO event);

    /** 订阅某场会话的本地事件流（热流）。 */
    Flux<ConversationEventVO> subscribe(String sessionId);

    /** 会话结束时释放这条会话的通道，避免 Sinks 越积越多。 */
    void remove(String sessionId);
}
package cn.adinall.ai.domain.coaching.conversation.adapter.port;

import cn.adinall.ai.domain.coaching.conversation.model.valobj.ConversationEventVO;
import reactor.core.publisher.Flux;

/**
 * 会话本地事件总线端口（补发「上游不给」的那几条事件）
 * <p>
 * 功能：让领域服务往某场会话的流里塞自己产生的事件，与上游事件汇进同一条 SSE 送给 App。
 * <pre>
 *   publish(String, ConversationEventVO)  往会话流里塞一条本地事件（没有订阅者时静默丢弃）
 *   subscribe(String)                     订阅该会话的本地事件流（热流）
 *   remove(String)                        会话结束时释放通道，避免 Sinks 越积越多
 * </pre>
 * 运行过程（一条 stt.final 的两条出口）：
 * <pre>
 *   ConversationServiceImpl.transcribeAndRecord(sessionId, pcm, 16000)
 *        |
 *        | avatarDriver.transcribeAudio(providerSessionId, pcm, 16000) --> text
 *        | appendUserTurn(sessionId, text) --> turnId
 *        v
 *   eventBus.publish(sessionId, ConversationEventVO.of(STT_FINAL,
 *           {"text": text, "turnId": turnId}))
 *        |
 *        | InProcessConversationEventBus（进程内 Sinks）
 *        v
 *   接口层：与 avatarDriver.events() 的流合并 --> SSE
 *        |
 *        v
 *   App 收到 stt.final --> 立刻收掉「正在识别」的转圈（不用等 SSE 往返）
 * </pre>
 * 案例：
 * <pre>
 *   输入: publish("s-9c1d2f3e", ConversationEventVO.of(EventTypeEnum.INTERRUPTED, Map.of()))
 *   处理: 进程内 Sinks 把这条事件推给当前所有订阅者
 *   输出: 订阅者拿到 {type=INTERRUPTED, data={}, ts=1730000000000}
 *         --> 客户端据此给「正在说」的字幕封口
 *   边界: 若此刻没有人订阅，publish 静默丢弃、不抛异常 —— 本地事件不是命令，丢了不影响业务
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>为什么需要一个「本地」总线：有些事件上游那条流里不会有 —— 语音上行识别出的用户台词
 *       （stt.final）由领域服务本地产生；打断回执也由领域服务统一下发一条本地 INTERRUPTED，
 *       让客户端的字幕封口不依赖上游（适配器同样会把上游 interrupted 映射进来，两条来源汇进同一条 SSE）。</li>
 *   <li>这些事件必须和上游事件汇进同一条 SSE，否则客户端要同时听两个来源、
 *       还要自己做去重与排序 —— 汇流是基础设施的事，领域只声明这个能力。</li>
 *   <li>没有订阅者时静默丢弃而不是缓冲：热流语义，晚订阅的人不该收到历史事件，
 *       否则重连后的客户端会看到上一轮的旧字幕。</li>
 *   <li>remove 不可省：会话按 sessionId 一条通道，不摘掉的话长时间运行会积累
 *       大量再无订阅者的 Sinks（内存泄漏）。</li>
 * </ul>
 */
public interface IConversationEventBusPort {

    /** 往某场会话的流里塞一条本地事件。没有订阅者时静默丢弃，不算错误。 */
    void publish(String sessionId, ConversationEventVO event);

    /** 订阅某场会话的本地事件流（热流）。 */
    Flux<ConversationEventVO> subscribe(String sessionId);

    /** 会话结束时释放这条会话的通道，避免 Sinks 越积越多。 */
    void remove(String sessionId);
}
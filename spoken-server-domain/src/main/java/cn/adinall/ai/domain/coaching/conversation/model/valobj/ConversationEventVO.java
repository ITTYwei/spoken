package cn.adinall.ai.domain.coaching.conversation.model.valobj;

/**
 * 会话事件值对象（一场会话里流转的领域事件）
 * <p>
 * 功能：承载「事件类型 + 载荷 + 时间戳」，是驱动事件流与本地事件流共用的统一形状。
 * <pre>
 *   type   事件类型（EventTypeEnum，决定对外 wire 名字）
 *   data   载荷：字幕类事件是 String，状态 / 错误类事件是 Map，允许为 null
 *   ts     事件产生时刻（毫秒）；of(...) 会填成当前时间
 *   of(EventTypeEnum, Object)   造一条事件并打上当前时间戳（推荐用这个，别直接 new）
 * </pre>
 * 数据流（两类来源汇进同一条 SSE）：
 * <pre>
 *   IAvatarDriverPort.events(providerSessionId)（上游 SSE）
 *        |
 *        | ConversationServiceImpl.applyDriverEvent：推进状态机、落回合、规整 payload
 *        v
 *   Flux&lt;ConversationEventVO&gt; --+
 *                               +--> 用例层合并 + HEARTBEAT --> SSE 给 App
 *   IConversationEventBusPort   |
 *        subscribe(sessionId) --+
 *        （本地补发：stt.final、interrupted）
 * </pre>
 * 案例：
 * <pre>
 *   输入: ConversationEventVO.of(EventTypeEnum.STT_FINAL,
 *             Map.of("text", "I went to the park.", "turnId", 1024L))
 *   处理: 打上 ts = System.currentTimeMillis()
 *   输出: {type=STT_FINAL, data={text=I went to the park., turnId=1024},
 *         ts=1730000000000}；序列化到 SSE 时 type 取 wire() = "stt.final"
 *   对照: ConversationEventVO.of(EventTypeEnum.ASSISTANT_DONE, "Hi! I'm Amy.")
 *         --> data 是整段 String（applyDriverEvent 会把 done 的载荷规整成整段）
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>data 刻意是 Object：不同事件的载荷形状本来就不同 —— 字幕事件是 String，
 *       状态 / 错误事件是 Map；给每种事件建一个 VO 会让「上游加一个事件」变成一次领域模型改动。</li>
 *   <li>载荷形状由领域服务在 applyDriverEvent 里规整，接口层不做业务判断：
 *       比如 assistant.done 的 data 承诺是整段文本，上游只给最后一块增量时也会被替换掉。</li>
 *   <li>ts 虽然由 of(...) 自动填，仍保留在 record 组件里：重放 / 测试需要一个确定的时间戳，
 *       不能只依赖「构造那一刻」。</li>
 * </ul>
 */
public record ConversationEventVO(EventTypeEnum type, Object data, long ts) {

    /** 根据事件类型与载荷创建会话事件。 */
    public static ConversationEventVO of(EventTypeEnum type, Object data) {
        return new ConversationEventVO(type, data, System.currentTimeMillis());
    }
}
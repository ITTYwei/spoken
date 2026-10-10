package cn.adinall.ai.domain.business.conversation.model.valobj;

/**
 * 一场会话里流转的事件（领域事件）。
 *
 * data 刻意是 Object：不同事件的载荷形状本来就不同 ——
 * 字幕事件是 String，状态/错误事件是 Map。用一个统一的 Object 装住，
 * 由接口层决定怎么序列化，比给每种事件建一个 VO 更贴近实际流量。
 */
public record ConversationEventVO(EventTypeEnum type, Object data, long ts) {

    public static ConversationEventVO of(EventTypeEnum type, Object data) {
        return new ConversationEventVO(type, data, System.currentTimeMillis());
    }
}
package cn.adinall.ai.domain.business.conversation.model.valobj;

/**
 * 会话事件类型。App 只认这里的 wire 取值；后端换实现时也必须映射到这些语义。
 *
 * wire 值与 OpenTalking 的 SSE 事件保持一致的命名风格（点号分隔），方便对接与排查。
 * 注意：枚举常量名不是契约，wire() 才是 —— 接口层只把 wire() 透出去。
 */
public enum EventTypeEnum {
    SESSION_QUEUED("session.queued"),
    SESSION_READY("session.ready"),
    SESSION_CLOSED("session.closed"),
    /** 用户语音的实时转写（未定稿），用于边说边显示字幕 */
    STT_PARTIAL("stt.partial"),
    /** 用户语音的最终转写 */
    STT_FINAL("stt.final"),
    /** 数字人回复的流式文本增量 */
    ASSISTANT_DELTA("assistant.delta"),
    ASSISTANT_DONE("assistant.done"),
    /** 开始播放某段语音 */
    TTS_START("tts.start"),
    /** 第一帧画面已送到客户端 */
    VIDEO_FIRST_FRAME("video.first_frame"),
    INTERRUPTED("interrupted"),
    ERROR("error"),
    HEARTBEAT("heartbeat");

    private final String wire;

    EventTypeEnum(String wire) {
        this.wire = wire;
    }

    /**
     * 返回这个事件在 SSE 上对外的名字（点号形式，如 session.ready）。
     */
    public String wire() {
        return wire;
    }

    /**
     * 按线上名字反查事件类型（大小写不敏感）。
     * 注意：认不出来时返回 ERROR 而不是 null —— 调用方拿到 ERROR 应当考虑「是不是上游加了新事件」，
     * 不要把它当成真的错误处理。
     */
    public static EventTypeEnum fromWire(String wire) {
        for (EventTypeEnum t : values()) {
            if (t.wire.equalsIgnoreCase(wire)) {
                return t;
            }
        }
        return ERROR;
    }
}
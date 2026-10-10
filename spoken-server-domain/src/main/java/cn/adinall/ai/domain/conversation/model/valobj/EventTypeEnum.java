package cn.adinall.ai.domain.conversation.model.valobj;

/**
 * 会话事件类型枚举（SSE 上对外的 wire 名字才是契约）
 * <p>
 * 功能：定义一场会话里能出现的全部事件类型，并提供「常量名 <-> 线上名字」的双向映射。
 * <pre>
 *   wire()             取这个事件在 SSE 上对外的名字（点号形式，如 session.ready）
 *   fromWire(String)   按线上名字反查类型，大小写不敏感；认不出来时返回 ERROR
 * </pre>
 * 事件清单（常量名 -- wire 值 -- 语义）：
 * <pre>
 *   SESSION_QUEUED      session.queued       上游受理了，但 GPU 槽位没空，还在排队
 *   SESSION_READY       session.ready        上游已就绪，可以交换 SDP
 *   SESSION_CLOSED      session.closed       会话已结束
 *   STT_PARTIAL         stt.partial          用户语音的实时转写（未定稿）
 *   STT_FINAL           stt.final            用户语音的最终转写（本地也会补一条）
 *   ASSISTANT_DELTA     assistant.delta      数字人回复的流式文本增量
 *   ASSISTANT_DONE      assistant.done       数字人回复收口（data 承诺是整段文本）
 *   ASSESSMENT_DONE     assessment.done      发音评测完成（分数 / 错词 / 韵律）
 *   TTS_START           tts.start            开始播放某段语音
 *   VIDEO_FIRST_FRAME   video.first_frame    第一帧画面已送到客户端
 *   INTERRUPTED         interrupted          打断回执（领域服务会补发一条本地事件）
 *   ERROR               error                出错（同时也是 fromWire 的兜底值）
 *   HEARTBEAT           heartbeat            SSE 保活（用例层每 15s 一条）
 * </pre>
 * 案例：
 * <pre>
 *   输入: fromWire("session.ready") / fromWire("ASSISTANT.DONE") / fromWire("video.avatar_x")
 *   处理: 逐个与枚举的 wire 值做 equalsIgnoreCase 比对
 *   输出: SESSION_READY / ASSISTANT_DONE / ERROR（最后一个是兜底，不代表真的出错）
 *   对照: EventTypeEnum.ASSISTANT_DONE.wire() = "assistant.done"
 *         EventTypeEnum.STT_FINAL.wire() = "stt.final"
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>枚举常量名不是契约，wire() 才是：接口层只把 wire() 透给 App，
 *       将来重命名常量不会破坏客户端，反过来则会。</li>
 *   <li>wire 值刻意与 OpenTalking 的 SSE 事件命名风格一致（点号分隔），
 *       对接与排查时两边能直接对上号。</li>
 *   <li>fromWire 认不出来时返回 ERROR 而不是 null：调用方拿到 ERROR 应当先考虑
 *       「是不是上游加了新事件」，直接 NPE 或静默丢弃都会更难查。</li>
 * </ul>
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
    /** 发音评测完成（data 是评测结果的对外形状） */
    ASSESSMENT_DONE("assessment.done"),
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
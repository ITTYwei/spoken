package cn.adinall.ai.domain.business.conversation.model.aggregate;

/**
 * 回合聚合根：一问一答。
 *
 * 生命周期是「先建后补」——用户说完先落一条只有 userText 的回合（要立刻拿到 turnId，
 * App 的跟读评测拿它当键），数字人回复定稿后再把 assistantText 和延迟补上去。
 * 所以它不是不可变对象，complete 之前 assistantText 就是 null。
 */
public class ConversationTurnAggregate {

    private Long id;
    private final String sessionId;
    private final int seq;
    private final String userText;
    private String assistantText;
    private String audioPath;
    private Long latencyMs;
    private final long createdAt;

    private ConversationTurnAggregate(Long id, String sessionId, int seq, String userText,
                                      String assistantText, String audioPath, Long latencyMs, long createdAt) {
        this.id = id;
        this.sessionId = sessionId;
        this.seq = seq;
        this.userText = userText;
        this.assistantText = assistantText;
        this.audioPath = audioPath;
        this.latencyMs = latencyMs;
        this.createdAt = createdAt;
    }

    /** 新建一条只有用户台词的回合；id 由数据库在落库时补上。 */
    public static ConversationTurnAggregate create(String sessionId, int seq, String userText) {
        return new ConversationTurnAggregate(null, sessionId, seq, userText, null, null, null,
                System.currentTimeMillis());
    }

    /** 从存储层还原一个回合（仓储实现用）。 */
    public static ConversationTurnAggregate restore(Long id, String sessionId, int seq, String userText,
                                                    String assistantText, String audioPath,
                                                    Long latencyMs, long createdAt) {
        return new ConversationTurnAggregate(id, sessionId, seq, userText, assistantText, audioPath,
                latencyMs, createdAt);
    }

    /**
     * 数字人回完了，补上整段回复和延迟。
     * latencyMs 为 null 时保留原值 —— 别把之前算好的延迟用 null 覆盖掉。
     */
    public void complete(String assistantText, Long latencyMs) {
        this.assistantText = assistantText;
        if (latencyMs != null) {
            this.latencyMs = latencyMs;
        }
    }

    /** 落库后由仓储回填自增主键。 */
    public void assignId(Long id) {
        this.id = id;
    }

    public Long getId() {
        return id;
    }

    public String getSessionId() {
        return sessionId;
    }

    public int getSeq() {
        return seq;
    }

    public String getUserText() {
        return userText;
    }

    public String getAssistantText() {
        return assistantText;
    }

    public String getAudioPath() {
        return audioPath;
    }

    public Long getLatencyMs() {
        return latencyMs;
    }

    public long getCreatedAt() {
        return createdAt;
    }
}
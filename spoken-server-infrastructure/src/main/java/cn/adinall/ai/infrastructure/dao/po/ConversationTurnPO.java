package cn.adinall.ai.infrastructure.dao.po;

/**
 * conversation_turn 表的持久化对象（一问一答）。
 *
 * id 是数据库自增主键，也就是对外的 turnId —— App 的跟读评测拿它当键，
 * 所以插入时必须回填（Mapper 里的 useGeneratedKeys）。
 */
public class ConversationTurnPO {

    private Long id;
    private String sessionId;
    private Integer seq;
    private String userText;
    private String assistantText;
    private String audioPath;
    private Long latencyMs;
    private Long createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public Integer getSeq() {
        return seq;
    }

    public void setSeq(Integer seq) {
        this.seq = seq;
    }

    public String getUserText() {
        return userText;
    }

    public void setUserText(String userText) {
        this.userText = userText;
    }

    public String getAssistantText() {
        return assistantText;
    }

    public void setAssistantText(String assistantText) {
        this.assistantText = assistantText;
    }

    public String getAudioPath() {
        return audioPath;
    }

    public void setAudioPath(String audioPath) {
        this.audioPath = audioPath;
    }

    public Long getLatencyMs() {
        return latencyMs;
    }

    public void setLatencyMs(Long latencyMs) {
        this.latencyMs = latencyMs;
    }

    public Long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Long createdAt) {
        this.createdAt = createdAt;
    }
}
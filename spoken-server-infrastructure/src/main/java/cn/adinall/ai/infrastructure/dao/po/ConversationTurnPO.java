package cn.adinall.ai.infrastructure.dao.po;

/**
 * conversation_turn 表的持久化对象（PO：一问一答占一行）
 * <p>
 * 功能：承载回合表的八个列，作为 MyBatis 的入参 / 出参；纯数据类，不含任何业务方法。
 * <pre>
 *   id             Long     自增主键，也是对外的 turnId
 *   sessionId      String   所属会话（列 session_id）
 *   seq            Integer  会话内第几轮（列 seq，同会话内递增）
 *   userText       String   用户这轮说的话（列 user_text）
 *   assistantText  String   数字人整段回复；未定稿时为 null（列 assistant_text）
 *   audioPath      String   用户音频落盘路径，可为 null（列 audio_path）
 *   latencyMs      Long     从用户说完到回复定稿的毫秒数（列 latency_ms）
 *   createdAt      Long     回合建立时刻的毫秒时间戳（列 created_at）
 * </pre>
 * 字段与列的对照（列名靠 MyBatis 下划线转驼峰自动映射，PO 上不写注解）：
 * <pre>
 *   ConversationTurnPO                  conversation_turn 表
 *        id            &lt;----------&gt;     id             自增主键
 *        sessionId     &lt;----------&gt;     session_id
 *        seq           &lt;----------&gt;     seq
 *        userText      &lt;----------&gt;     user_text
 *        assistantText &lt;----------&gt;     assistant_text
 *        audioPath     &lt;----------&gt;     audio_path
 *        latencyMs     &lt;----------&gt;     latency_ms
 *        createdAt     &lt;----------&gt;     created_at
 * </pre>
 * 案例：
 * <pre>
 *   插入前（append 组装的 PO，id 交给数据库）:
 *     {"id":null, "sessionId":"s-3f9c1a77e2b04d15", "seq":3,
 *      "userText":"I want a coffee", "assistantText":null, "audioPath":null,
 *      "latencyMs":null, "createdAt":1730000000100}
 *   插入后（Mapper 回填 id）:
 *     {"id":42, "sessionId":"s-3f9c1a77e2b04d15", "seq":3,
 *      "userText":"I want a coffee", "assistantText":null, "audioPath":null,
 *      "latencyMs":null, "createdAt":1730000000100}
 *   回复定稿后（updateAssistant 只改两个字段）:
 *     {"id":42, "assistantText":"Sure, what would you like?", "latencyMs":1850}
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>id 是数据库自增主键，也正是对外的 turnId —— App 的跟读评测拿它当键，
 *       所以插入时必须回填（Mapper 里的 useGeneratedKeys / keyProperty）。</li>
 *   <li>保留 null 语义而不给默认值：assistantText 为 null 表示这轮还没回完，
 *       audioPath 为 null 表示没留音频；填成空串会让「还没回复」与「回复为空」分不清。</li>
 *   <li>时间戳用 Long（毫秒）而不是 LocalDateTime：与建表脚本的 bigint 一致，跨时区无歧义。</li>
 *   <li>这是 PO 不是领域对象：没有行为、没有校验、只有 getter/setter ——
 *       状态流转（先建后补）由 ConversationTurnAggregate 负责。</li>
 * </ul>
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
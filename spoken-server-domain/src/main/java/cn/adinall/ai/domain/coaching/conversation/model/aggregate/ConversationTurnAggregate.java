package cn.adinall.ai.domain.coaching.conversation.model.aggregate;

/**
 * 回合聚合根（一问一答：userText + assistantText）
 * <p>
 * 功能：记录一条对话回合。生命周期是「先建后补」—— 用户说完先落一条只有 userText 的回合
 * （要立刻拿到自增 id，App 的跟读评测拿它当键），数字人回复定稿后再补 assistantText 和延迟。
 * <pre>
 *   create(sessionId, seq, userText)   新建：assistantText / audioPath / latencyMs 都是 null
 *   restore(id, sessionId, seq, ...)   从存储层还原（仓储实现用）
 *   complete(assistantText, latencyMs) 补回复与延迟；latencyMs 为 null 时保留原值
 *   assignId(Long)                     落库后由仓储回填自增主键
 *   getSeq() / getUserText() / getAssistantText() / getAudioPath() / getLatencyMs()
 * </pre>
 * 运行过程（一条回合的两次写）：
 * <pre>
 *   ConversationServiceImpl.transcribeAndRecord(sessionId, pcm, 16000)
 *        |
 *        | appendUserTurn(sessionId, text)
 *        |        |
 *        |        | turnRepository.findLastBySessionId(sessionId) --> 算 nextSeq
 *        |        v
 *        |   ConversationTurnAggregate.create(sessionId, nextSeq, "I went to the park.")
 *        |        |
 *        |        v
 *        |   turnRepository.append(turn) --> assignId(1024)
 *        |
 *        | 事件流: assistant.delta ... assistant.done
 *        v
 *   completeAssistantTurn(sessionId, "That sounds fun! What did you do there?", 1850L)
 *        |
 *        | 取 seq 最大的那条回合（一条都没有时新建 seq=0 的回合只装回复）
 *        v
 *   turn.complete(...) --> turnRepository.updateAssistantText(turn)
 * </pre>
 * 案例：
 * <pre>
 *   输入: ConversationTurnAggregate.create("s-9c1d2f3e", 2, "I went to the park.")
 *   处理: turnRepository.append 落库后 assignId(1024L)
 *         数字人回完 --> turn.complete("That sounds fun! What did you do there?", 1850L)
 *   输出: {id=1024, sessionId="s-9c1d2f3e", seq=2, userText="I went to the park.",
 *         assistantText="That sounds fun! What did you do there?",
 *         audioPath=null, latencyMs=1850, createdAt=1730000000000}
 *         complete 之前 assistantText 就是 null —— 它不是不可变对象
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>必须先落库拿到自增 id：App 的跟读评测以 turnId 为键（assessment.turn_id），
 *       先在内存里造一个假 id 会让评分落到别的回合甚至找不到回合。</li>
 *   <li>complete 里 latencyMs 为 null 时保留原值：事件里拿不到延迟时，
 *       不能把之前已经算好的延迟覆盖成 null。</li>
 *   <li>seq 由领域服务用 findLastBySessionId 的结果 +1 推出来，本类只承载它 ——
 *       以后要换成数据库序列，只需改 appendUserTurn 一处。</li>
 *   <li>audioPath 暂时没有写入路径（保留字段）：口语训练的录音归档是后续能力，
 *       先留着可以避免以后加一个字段就要动 restore / 表结构两处。</li>
 * </ul>
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

    /** 创建 {@code ConversationTurnAggregate}，初始化其依赖与状态。 */
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

    /** 读取记录标识。 */
    public Long getId() {
        return id;
    }

    /** 读取业务会话标识。 */
    public String getSessionId() {
        return sessionId;
    }

    /** 读取回合顺序号。 */
    public int getSeq() {
        return seq;
    }

    /** 读取用户原话。 */
    public String getUserText() {
        return userText;
    }

    /** 读取助手已播文本。 */
    public String getAssistantText() {
        return assistantText;
    }

    /** 读取音频存储路径。 */
    public String getAudioPath() {
        return audioPath;
    }

    /** 读取响应耗时（毫秒）。 */
    public Long getLatencyMs() {
        return latencyMs;
    }

    /** 读取创建时间。 */
    public long getCreatedAt() {
        return createdAt;
    }
}
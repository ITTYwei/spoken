package cn.adinall.ai.domain.conversation.model.aggregate;

import cn.adinall.ai.domain.conversation.model.valobj.PronunciationResultVO;

/**
 * 一次跟读评测的聚合（评分结论 + 它属于哪场会话哪个回合）
 * <p>
 * 功能：把「这次评测是谁在什么时候、针对哪句题面、念出了什么结果」绑成一个整体，
 * 落库时作为一行；id 由数据库自增，落库后回填。
 * <pre>
 *   id            自增主键（落库后回填）；对外就是 assessmentId，App 拿它做记录列表的 key
 *   sessionId     所属会话（关联 training_session.id）；必填 —— 没有会话的评测无处安放
 *   turnId        所属回合（关联 conversation_turn.id）；可为 null（刚录完、回合还没建）
 *   expectedText  这次跟读的题面（原句）
 *   lang          实际使用的语言码（已兜底，不是 null）
 *   contentType   上传音频的 MIME，仅留档
 *   audioBytes    音频字节数（留档，复盘时能看出「是不是录得太短」）
 *   result        上游给出的完整评测结果（分数、错词、韵律都在里面）
 *   latencyMs     调一次评测的耗时（毫秒），用于容量评估
 *   createdAt     创建时间（epoch 毫秒）
 * </pre>
 * 体系架构（一次跟读评测的落库路径）：
 * <pre>
 *   PronunciationServiceImpl.assess(sessionId, turnId, command)
 *        |
 *        | (1) port.assess(command) --> PronunciationResultVO
 *        | (2) 记耗时 latencyMs
 *        | (3) PronunciationAssessmentAggregate.create(...)   // id == null
 *        v
 *   repository.save(assessment)
 *        |
 *        | INSERT pronunciation_assessment (...) useGeneratedKeys 回填 id
 *        | result 序列化成 JSON 写进 result_json 列
 *        v
 *   assessment.assignId(生成的主键)
 *        |
 *        v
 *   用例层把它翻成 PronunciationAssessmentResponseDTO 返回给 App
 * </pre>
 * 案例：
 * <pre>
 *   输入: sessionId="s-3f9c1a77e2b04d15", turnId=null, expectedText="Hello, how are you?",
 *         lang="en", result={score:86.67, errors:[], ...}, latencyMs=192
 *   处理: save() -> INSERT 一行 -> 自增 id=1 回填
 *   输出: 同一个聚合，getId()=1L；App 拿到 assessmentId=1 存进错题本
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>result 整体存成 JSON 而不是把 score / errors 摊成几十列：上游的响应形状已经漂移过一次，
 *       摊平意味着它每加一个字段我这边就要改表；而「按会话/回合查评分」只需要几个标量列，
 *       所以表里另存了 score / transcribe / 两个错误率 / 声学距离 这几个会用于查询与展示的列，
 *       其余（错词明细、韵律、音素向量）整份 JSON 留档就够了。</li>
 *   <li>聚合持有的是 PronunciationResultVO 而不是 JSON 字符串：JSON 是存储细节，
 *       由仓储去序列化 —— 领域里出现 JSON 字符串，等于把「上游长什么样」泄漏到了领域。</li>
 *   <li>turnId 允许为 null：App 可能先跟读、后建回合（或者这次跟读不挂在任何回合上），
 *       强行要求非空会把一条合法路径堵死。</li>
 *   <li>本聚合是不可变的（除 id）：评测一旦完成就不会再变，不存在 ConversationTurnAggregate
 *       那种「先建后补」的两段式生命周期。</li>
 * </ul>
 */
public class PronunciationAssessmentAggregate {

    private Long id;
    private final String sessionId;
    private final Long turnId;
    private final String expectedText;
    private final String lang;
    private final String contentType;
    private final int audioBytes;
    private final PronunciationResultVO result;
    private final Long latencyMs;
    private final long createdAt;

    private PronunciationAssessmentAggregate(Long id, String sessionId, Long turnId, String expectedText,
                                             String lang, String contentType, int audioBytes,
                                             PronunciationResultVO result, Long latencyMs, long createdAt) {
        this.id = id;
        this.sessionId = sessionId;
        this.turnId = turnId;
        this.expectedText = expectedText;
        this.lang = lang;
        this.contentType = contentType;
        this.audioBytes = audioBytes;
        this.result = result;
        this.latencyMs = latencyMs;
        this.createdAt = createdAt;
    }

    /** 新建一条评测记录；id 由数据库在落库时补上。 */
    public static PronunciationAssessmentAggregate create(String sessionId, Long turnId, String expectedText,
                                                         String lang, String contentType, int audioBytes,
                                                         PronunciationResultVO result, Long latencyMs) {
        return new PronunciationAssessmentAggregate(null, sessionId, turnId, expectedText, lang, contentType,
                audioBytes, result, latencyMs, System.currentTimeMillis());
    }

    /** 从存储层还原一条评测记录（仓储实现用）。 */
    public static PronunciationAssessmentAggregate restore(Long id, String sessionId, Long turnId,
                                                          String expectedText, String lang, String contentType,
                                                          int audioBytes, PronunciationResultVO result,
                                                          Long latencyMs, long createdAt) {
        return new PronunciationAssessmentAggregate(id, sessionId, turnId, expectedText, lang, contentType,
                audioBytes, result, latencyMs, createdAt);
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

    public Long getTurnId() {
        return turnId;
    }

    public String getExpectedText() {
        return expectedText;
    }

    public String getLang() {
        return lang;
    }

    public String getContentType() {
        return contentType;
    }

    public int getAudioBytes() {
        return audioBytes;
    }

    public PronunciationResultVO getResult() {
        return result;
    }

    public Long getLatencyMs() {
        return latencyMs;
    }

    public long getCreatedAt() {
        return createdAt;
    }
}

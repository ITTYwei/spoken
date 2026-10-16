package cn.adinall.ai.infrastructure.dao.po;

import java.time.LocalDateTime;

/**
 * pronunciation_assessment 表的行映射对象（跟读评测记录）
 * <p>
 * 功能：表结构与 Java 字段的一比一映射，由 MyBatis 按 map-underscore-to-camel-case 自动装配，
 * 不写 resultMap。自增主键 id 由 insert 回填。
 * <pre>
 *   id                自增主键（对外就是 assessmentId）
 *   sessionId         所属会话（training_session.id）
 *   turnId            所属回合（conversation_turn.id），可为 null
 *   expectedText      本次跟读的题面
 *   lang              实际使用的语言码
 *   contentType       上传音频的 MIME
 *   audioBytes        音频字节数
 *   score             总分 0~100（取自结果里的 score，单独成列是为了能按分数查询/排序）
 *   transcribe        词识别器听到的整句
 *   wordErrorRate     词错误率 0~1
 *   phonemeErrorRate  音素错误率 0~1
 *   acousticDistance  声学 DTW 均距
 *   feedback          上游给的一句人话反馈
 *   resultJson        完整评测结果的 JSON（错词明细、韵律曲线、音素向量都在里面）
 *   latencyMs         调用评测的耗时（毫秒）
 *   createdAt         数据库创建时间（DATETIME，秒精度）
 * </pre>
 * 体系架构：
 * <pre>
 *   PronunciationAssessmentRepository（唯一调用方）
 *        |
 *        | save():     聚合 -> PO（result 序列化成 resultJson）-> insert -> po.getId() 回填聚合
 *        | findById(): select -> toAggregate()（resultJson 反序列化回 PronunciationResultVO）
 *        v
 *   IPronunciationAssessmentDao（@Mapper） --> PronunciationAssessmentMapper.xml
 *        |
 *        v
 *   pronunciation_assessment 表（id 自增，表结构见 Flyway 迁移 V3）
 * </pre>
 * 案例：
 * <pre>
 *   输入: insert 的 PO = {id=null, sessionId="s-3f9c1a77e2b04d15", turnId=null,
 *         expectedText="Hello, how are you?", lang="en", contentType="audio/wav",
 *         audioBytes=37940, score=86.67, transcribe="HELLO HOW ARE YOU",
 *         wordErrorRate=0.0, phonemeErrorRate=0.3333, acousticDistance=0.0,
 *         feedback="🔊 Feedback ...", resultJson="{...}", latencyMs=192,
 *         createdAt=1767225600000}
 *   处理: Mapper 执行 insert，数据库分配自增 id=1
 *   输出: 返回受影响行数 1；副作用是 po.getId() 变成 1L，且该表多一行
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>score / transcribe / 两个错误率 / 声学距离既在 result_json 里也单独成列：JSON 那份是
 *       「完整留档」（上游加字段不用改表），单独这几列是为了能直接 SQL 查询与排序
 *       （记录页按分数、复盘统计平均分），从 JSON 里挖值的 SQL 又慢又难读。</li>
 *   <li>resultJson 用 LONGTEXT 而不是 JSON 类型：MySQL 的 JSON 列会对内容做校验与重排，
 *       而上游字段名与结构还在漂移，留成文本最不容易在「上游改结构」时把自己卡住。</li>
 *   <li>所有数值列都允许为 null：上游可能不给某个数值，用 0 顶替会把「没算出来」说成「0 分」。</li>
 *   <li>字段一律用包装类型（Long/Double/Integer）：老数据的列可能是 NULL，
 *       用基本类型会在 MyBatis 赋值时炸 null 拆箱。</li>
 * </ul>
 */
public class PronunciationAssessmentPO {

    /** 自增主键。 */
    private Long id;
    /** 所属会话 id。 */
    private String sessionId;
    /** 所属回合 id，可为 null。 */
    private Long turnId;
    /** 本次跟读的题面。 */
    private String expectedText;
    /** 实际使用的语言码。 */
    private String lang;
    /** 上传音频的 MIME。 */
    private String contentType;
    /** 音频字节数。 */
    private Integer audioBytes;
    /** 总分 0~100。 */
    private Double score;
    /** 词识别器听到的整句。 */
    private String transcribe;
    /** 词错误率 0~1。 */
    private Double wordErrorRate;
    /** 音素错误率 0~1。 */
    private Double phonemeErrorRate;
    /** 声学 DTW 均距。 */
    private Double acousticDistance;
    /** 上游给的一句人话反馈。 */
    private String feedback;
    /** 完整评测结果的 JSON。 */
    private String resultJson;
    /** 调用评测的耗时（毫秒）。 */
    private Long latencyMs;
    /** 数据库自动生成的审计时间（Asia/Shanghai）。 */
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /** 读取记录标识。 */
    public Long getId() {
        return id;
    }

    /** 设置记录标识。 */
    public void setId(Long id) {
        this.id = id;
    }

    /** 读取业务会话标识。 */
    public String getSessionId() {
        return sessionId;
    }

    /** 设置业务会话标识。 */
    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    /** 读取对话回合标识。 */
    public Long getTurnId() {
        return turnId;
    }

    /** 设置对话回合标识。 */
    public void setTurnId(Long turnId) {
        this.turnId = turnId;
    }

    /** 读取发音评估参考文本。 */
    public String getExpectedText() {
        return expectedText;
    }

    /** 设置发音评估参考文本。 */
    public void setExpectedText(String expectedText) {
        this.expectedText = expectedText;
    }

    /** 读取语言代码。 */
    public String getLang() {
        return lang;
    }

    /** 设置语言代码。 */
    public void setLang(String lang) {
        this.lang = lang;
    }

    /** 读取音频媒体类型。 */
    public String getContentType() {
        return contentType;
    }

    /** 设置音频媒体类型。 */
    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    /** 读取音频字节数。 */
    public Integer getAudioBytes() {
        return audioBytes;
    }

    /** 设置音频字节数。 */
    public void setAudioBytes(Integer audioBytes) {
        this.audioBytes = audioBytes;
    }

    /** 读取综合评分。 */
    public Double getScore() {
        return score;
    }

    /** 设置综合评分。 */
    public void setScore(Double score) {
        this.score = score;
    }

    /** 读取识别文本。 */
    public String getTranscribe() {
        return transcribe;
    }

    /** 设置识别文本。 */
    public void setTranscribe(String transcribe) {
        this.transcribe = transcribe;
    }

    /** 读取单词错误率。 */
    public Double getWordErrorRate() {
        return wordErrorRate;
    }

    /** 设置单词错误率。 */
    public void setWordErrorRate(Double wordErrorRate) {
        this.wordErrorRate = wordErrorRate;
    }

    /** 读取音素错误率。 */
    public Double getPhonemeErrorRate() {
        return phonemeErrorRate;
    }

    /** 设置音素错误率。 */
    public void setPhonemeErrorRate(Double phonemeErrorRate) {
        this.phonemeErrorRate = phonemeErrorRate;
    }

    /** 读取声学距离。 */
    public Double getAcousticDistance() {
        return acousticDistance;
    }

    /** 设置声学距离。 */
    public void setAcousticDistance(Double acousticDistance) {
        this.acousticDistance = acousticDistance;
    }

    /** 读取发音反馈。 */
    public String getFeedback() {
        return feedback;
    }

    /** 设置发音反馈。 */
    public void setFeedback(String feedback) {
        this.feedback = feedback;
    }

    /** 读取完整评估结果 JSON。 */
    public String getResultJson() {
        return resultJson;
    }

    /** 设置完整评估结果 JSON。 */
    public void setResultJson(String resultJson) {
        this.resultJson = resultJson;
    }

    /** 读取响应耗时（毫秒）。 */
    public Long getLatencyMs() {
        return latencyMs;
    }

    /** 设置响应耗时（毫秒）。 */
    public void setLatencyMs(Long latencyMs) {
        this.latencyMs = latencyMs;
    }

    /** 读取创建时间。 */
    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    /** 设置创建时间。 */
    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    /** 读取更新时间。 */
    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    /** 设置更新时间。 */
    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}

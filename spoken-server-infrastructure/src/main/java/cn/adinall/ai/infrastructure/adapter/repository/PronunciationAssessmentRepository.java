package cn.adinall.ai.infrastructure.adapter.repository;

import cn.adinall.ai.domain.conversation.adapter.repository.IPronunciationAssessmentRepository;
import cn.adinall.ai.domain.conversation.model.aggregate.PronunciationAssessmentAggregate;
import cn.adinall.ai.domain.conversation.model.valobj.PronunciationResultVO;
import cn.adinall.ai.infrastructure.dao.IPronunciationAssessmentDao;
import cn.adinall.ai.infrastructure.dao.po.PronunciationAssessmentPO;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 评测记录仓储实现（IPronunciationAssessmentRepository）：领域聚合与 pronunciation_assessment 表互转
 * <p>
 * 功能：评测记录的落库与读取；核心契约是 save 之后聚合必须带上数据库生成的自增 assessmentId。
 * <pre>
 *   save(assessment)        插一条记录（@Transactional），返回入参聚合，id 已回填
 *   findById(id)            按主键查单条，PO 用 restore 还原成聚合
 *   listBySessionId(sid)    按会话查全部，创建时间倒序
 *   listByTurnId(turnId)    按回合查，创建时间倒序
 * </pre>
 * 运行过程（一次评测结果的落库与读回）：
 * <pre>
 *   save(assessment)
 *        |
 *        | (1) 聚合 -> PO：标量字段逐个抄；result（PronunciationResultVO）用 Jackson
 *        |     序列化成 JSON 写进 result_json
 *        |
 *        | (2) assessmentDao.insert(po)
 *        |        Mapper: useGeneratedKeys="true" keyProperty="id" 把自增主键写回 po
 *        |
 *        | (3) assessment.assignId(po.getId())
 *        v
 *   聚合带上 assessmentId
 *
 *   listBySessionId(sid)
 *        |
 *        +--> assessmentDao.selectBySessionIdDesc(sid) --> List&lt;PO&gt;
 *        +--> toAggregate(po)：result_json 反序列化回 PronunciationResultVO
 *        v
 *   记录页能看到每次跟读的分数、错词与韵律
 * </pre>
 * 案例：
 * <pre>
 *   输入: save(PronunciationAssessmentAggregate.create("s-3f9c1a77e2b04d15", null,
 *              "Hello, how are you?", "en", "audio/wav", 37940, result, 192L))
 *         （result.score=86.67, errors=[], prosody.f0 38 个点）
 *   处理: result 序列化成 result_json -> INSERT 一行 -> 自增 id=1
 *   输出: 同一个聚合，getId()=1L
 *   续: listBySessionId("s-3f9c1a77e2b04d15") 返回这条，getResult().score() 仍是 86.67，
 *       getResult().errors() 仍是空列表（不是 null）
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>save 返回入参聚合而不是新建对象：调用方拿到的就是被 assignId 回填过的那个实例 ——
 *       与 ConversationTurnRepository.append 同一约定，两个仓储的行为不该长得不一样。</li>
 *   <li>读回来用 restore 而不是 create：create 会把 id 置空、createdAt 置成当前时刻，
 *       记录页里每条的 id 都会变 null、时间都变成「刚刚」，整个复盘就废了。</li>
 *   <li>result_json 反序列化失败（老数据、手工改过、PronunciationResultVO 结构改过）不抛异常：
 *       只记一条 warn 并把 result 置为 null，其余标量字段照常返回 ——
 *       一条记录读不回来不该让整个记录页 500。</li>
 *   <li>PO 里的标量列（score / transcribe / 两个错误率 / 声学距离）与 result_json 是同一份数据的
 *       两种投影，写入时同源（都来自聚合的 result），所以不会分叉；它们存在的意义是
 *       让「按会话查、按分数排序、统计平均分」这类查询不必解析 JSON。</li>
 *   <li>反序列化用宽松 ObjectMapper（FAIL_ON_UNKNOWN_PROPERTIES=false）：
 *       如果我们删掉/改名了 PronunciationResultVO 的某个组件，老 JSON 里多出来的字段不该让读取失败。</li>
 * </ul>
 */
@Repository
public class PronunciationAssessmentRepository implements IPronunciationAssessmentRepository {

    private static final Logger log = LoggerFactory.getLogger(PronunciationAssessmentRepository.class);

    /**
     * 结果 JSON 的序列化器。
     *
     * 不复用 OpenTalkingJson.MAPPER：那个是「跟上游说话」用的宽松方言，与「自己的存档格式」
     * 是两件事，将来要给存档加版本字段或改命名策略时不该互相牵连。
     */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final IPronunciationAssessmentDao assessmentDao;

    public PronunciationAssessmentRepository(IPronunciationAssessmentDao assessmentDao) {
        this.assessmentDao = assessmentDao;
    }

    /**
     * 落一条评测记录并回填自增主键。
     * 流程：聚合转 PO（result 序列化进 result_json）-> insert（MyBatis 回填 key）-> 把 id 交回聚合。
     */
    @Override
    @Transactional
    public PronunciationAssessmentAggregate save(PronunciationAssessmentAggregate assessment) {
        PronunciationResultVO result = assessment.getResult();

        PronunciationAssessmentPO po = new PronunciationAssessmentPO();
        po.setSessionId(assessment.getSessionId());
        po.setTurnId(assessment.getTurnId());
        po.setExpectedText(assessment.getExpectedText());
        po.setLang(assessment.getLang());
        po.setContentType(assessment.getContentType());
        po.setAudioBytes(assessment.getAudioBytes());
        po.setScore(result == null ? null : result.score());
        po.setTranscribe(result == null ? null : result.transcribe());
        po.setWordErrorRate(result == null ? null : result.wordErrorRate());
        po.setPhonemeErrorRate(result == null ? null : result.phonemeErrorRate());
        po.setAcousticDistance(result == null ? null : result.acousticDistance());
        po.setFeedback(result == null ? null : result.feedback());
        po.setResultJson(toJson(result));
        po.setLatencyMs(assessment.getLatencyMs());
        po.setCreatedAt(assessment.getCreatedAt());

        assessmentDao.insert(po);
        assessment.assignId(po.getId());
        return assessment;
    }

    @Override
    public Optional<PronunciationAssessmentAggregate> findById(Long assessmentId) {
        return Optional.ofNullable(assessmentDao.selectById(assessmentId))
                .map(PronunciationAssessmentRepository::toAggregate);
    }

    @Override
    public List<PronunciationAssessmentAggregate> listBySessionId(String sessionId) {
        return toAggregates(assessmentDao.selectBySessionIdDesc(sessionId));
    }

    @Override
    public List<PronunciationAssessmentAggregate> listByTurnId(Long turnId) {
        return toAggregates(assessmentDao.selectByTurnId(turnId));
    }

    // ------------------------------------------------------------ 转换

    private static List<PronunciationAssessmentAggregate> toAggregates(List<PronunciationAssessmentPO> rows) {
        List<PronunciationAssessmentAggregate> result = new ArrayList<>();
        if (rows == null) {
            return result;
        }
        for (PronunciationAssessmentPO row : rows) {
            result.add(toAggregate(row));
        }
        return result;
    }

    /** PO -> 聚合。用 restore：id 与 createdAt 必须是库里的原值。 */
    private static PronunciationAssessmentAggregate toAggregate(PronunciationAssessmentPO po) {
        return PronunciationAssessmentAggregate.restore(
                po.getId(),
                po.getSessionId(),
                po.getTurnId(),
                po.getExpectedText(),
                po.getLang(),
                po.getContentType(),
                po.getAudioBytes() == null ? 0 : po.getAudioBytes(),
                fromJson(po.getResultJson(), po.getId()),
                po.getLatencyMs(),
                po.getCreatedAt() == null ? System.currentTimeMillis() : po.getCreatedAt());
    }

    /** 结果对象 -> JSON；为 null 时存 null（列允许为空）。 */
    private static String toJson(PronunciationResultVO result) {
        if (result == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(result);
        } catch (Exception e) {
            // 序列化失败属于我方 bug（VO 里放了不可序列化的东西），但不能因此丢掉整条记录：
            // 标量列照常落库，JSON 留空，读取时 result 为 null。
            log.warn("评测结果序列化失败，仅落库标量字段: {}", e.toString());
            return null;
        }
    }

    /**
     * JSON -> 结果对象。
     * 反序列化失败只记 warn 并返回 null：一条坏记录不该让整个记录页 500。
     */
    private static PronunciationResultVO fromJson(String json, Long assessmentId) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, PronunciationResultVO.class);
        } catch (Exception e) {
            log.warn("评测记录 {} 的结果 JSON 解析失败，只返回标量字段: {}", assessmentId, e.toString());
            return null;
        }
    }
}

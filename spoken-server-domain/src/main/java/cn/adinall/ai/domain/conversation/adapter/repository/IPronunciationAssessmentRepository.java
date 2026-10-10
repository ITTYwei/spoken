package cn.adinall.ai.domain.conversation.adapter.repository;

import cn.adinall.ai.domain.conversation.model.aggregate.PronunciationAssessmentAggregate;

import java.util.List;
import java.util.Optional;

/**
 * 跟读评测记录仓储（评分的持久化出口）
 * <p>
 * 功能：把「哪场会话、哪个回合、什么题面、念了多少分」落库并读回来，供记录页、错题本与复盘使用。
 * <pre>
 *   save(PronunciationAssessmentAggregate)          落一条评测，返回回填了自增主键的聚合
 *   findById(Long)                                  按评测 id 取一条
 *   listBySessionId(String)                         取某场会话的全部评测（按时间倒序）
 *   listByTurnId(Long)                              取挂在某个回合上的评测
 * </pre>
 * 体系架构：
 * <pre>
 *   PronunciationServiceImpl.assess(...)
 *        |
 *        | 上游评测完成（拿到 PronunciationResultVO、记下耗时）
 *        v
 *   save(assessment)
 *        |
 *        | INSERT pronunciation_assessment (...)
 *        |   result 序列化成 JSON 写进 result_json
 *        |   useGeneratedKeys 把自增 id 回填到聚合
 *        v
 *   聚合带着 assessmentId 回到用例层 -> 响应的 assessmentId 就是它
 *
 *   GET /api/v1/sessions/{id}/pronunciation
 *        |
 *        v
 *   listBySessionId(sessionId) --> 逐条还原成聚合（result_json 反序列化）
 * </pre>
 * 案例：
 * <pre>
 *   输入: save(PronunciationAssessmentAggregate.create("s-3f9c1a77e2b04d15", null,
 *              "Hello, how are you?", "en", "audio/wav", 37940, result, 192L))
 *   处理: INSERT 一行（id 由数据库生成）-> 回填到聚合
 *   输出: 同一个聚合，getId()=1L
 *   续: listBySessionId("s-3f9c1a77e2b04d15") 返回这条，getResult().score() 仍是 86.67
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>save 返回入参聚合而不是新对象：调用方拿到的就是被回填过 id 的那个实例 ——
 *       与 IConversationTurnRepository.append 同一约定，两个仓储的行为不该长得不一样。</li>
 *   <li>读回来必须走 restore 而不是 create：create 会把 id 置空、createdAt 置成当前时刻，
 *       那样列表里每条的 id 都是 null、时间都变成「刚刚」，记录页就废了。</li>
 *   <li>列表按时间倒序：记录页默认看最近一次跟读，倒序能省掉客户端一次排序。</li>
 * </ul>
 */
public interface IPronunciationAssessmentRepository {

    /** 落一条评测记录，返回回填了自增主键的聚合。 */
    PronunciationAssessmentAggregate save(PronunciationAssessmentAggregate assessment);

    /** 按评测 id 取一条。 */
    Optional<PronunciationAssessmentAggregate> findById(Long assessmentId);

    /** 取某场会话的全部评测，按创建时间倒序（最近的在前）。 */
    List<PronunciationAssessmentAggregate> listBySessionId(String sessionId);

    /**
     * 取挂在某个回合上的评测。
     * 用途：App 拿到一个 turnId 后回看「这一轮跟读当时得了多少分」。
     */
    List<PronunciationAssessmentAggregate> listByTurnId(Long turnId);
}

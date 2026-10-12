package cn.adinall.ai.domain.pronunciation.service;

import cn.adinall.ai.domain.pronunciation.model.aggregate.PronunciationAssessmentAggregate;
import cn.adinall.ai.domain.pronunciation.model.entity.AssessPronunciationCommandEntity;

import java.util.List;

/**
 * 发音评测领域服务（跟读评分的唯一入口）
 * <p>
 * 功能：编排「校验入参 -&gt; 调评测端口 -&gt; 记耗时 -&gt; 落库」这一串动作，
 * 并把「一次跟读必须留下一条记录」这条不变式固定在这里。
 * <pre>
 *   assess(sessionId, turnId, command)   评测并落库，返回带 assessmentId 的聚合
 *   listBySessionId(sessionId)           取某场会话的评测记录（最近的在前）
 *   listByTurnId(turnId)                 取某个回合的评测记录
 * </pre>
 * 体系架构（它在链路中的位置）：
 * <pre>
 *   PronunciationCaseImpl（用例：决定「哪场会话、哪个回合」，翻 DTO）
 *        |
 *        v
 *   IPronunciationService（本接口：规则与不变式，不做编排等待）
 *        |
 *        +--> IPronunciationPort.assess(command)         上游评测（可能抛 AppException）
 *        +--> IPronunciationAssessmentRepository.save()  落库并回填 id
 * </pre>
 * 案例：
 * <pre>
 *   输入: assess("s-3f9c1a77e2b04d15", null, command{audio=37940B, expectedText="Hello, how are you?"})
 *   处理: 入参校验 -> 端口评测（实测 ~0.19s，上游跑在 GPU 上）-> 记 latencyMs -> 落库
 *   输出: PronunciationAssessmentAggregate{id=1, score=86.67, latencyMs=192, ...}
 *   失败: 音频为空       -> AppException{code="invalid_request"}（400）
 *         题面为空       -> AppException{code="invalid_request"}（400）
 *         音频超上限     -> AppException{code="audio_too_large"}（413）
 *         上游不可达     -> AppException{code="provider_unavailable"}（503，由适配器抛）
 *         上游结果不可用 -> AppException{code="pronunciation_failed"}（502）
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>「先评测、后落库」而不是「先落一条再回填」：评测可能直接失败，先插一条空记录
 *       会留下大量 score 为 null 的废行，而记录页还得为此加过滤条件。</li>
 *   <li>落库失败要不要吞掉？不吞。评分是这门课的核心产出，落库失败却回 200 会让 App
 *       显示分数、用户却查不到记录 —— 宁可这一次跟读报错重来。</li>
 *   <li>耗时（latencyMs）在这里记而不是在适配器里：适配器记的是「HTTP 那一跳」，
 *       这里记的是「用户等了多少」——将来加缓存或重试时，两个数会分叉，分开记才复盘得清。</li>
 *   <li>会话是否存在不在这里校验：本服务只认 sessionId 这个字符串，查会话需要会话仓储，
 *       那是用例层有的东西（ConversationCaseImpl 已经注入 IConversationService）。
 *       领域服务保持「只依赖自己需要的那几个端口」。</li>
 * </ul>
 */
public interface IPronunciationService {

    /**
     * 评测一次跟读并落库。
     *
     * @param sessionId 所属会话 id（必填，由用例层保证真实存在）
     * @param turnId    所属回合 id，可为 null（这次跟读不挂回合）
     * @param command   录音 + 题面 + 语言
     * @return 带自增 assessmentId 的评测聚合
     */
    PronunciationAssessmentAggregate assess(String sessionId, Long turnId, AssessPronunciationCommandEntity command);

    /** 取某场会话的全部评测记录，按创建时间倒序。 */
    List<PronunciationAssessmentAggregate> listBySessionId(String sessionId);

    /** 取挂在某个回合上的评测记录。 */
    List<PronunciationAssessmentAggregate> listByTurnId(Long turnId);
}

package cn.adinall.ai.domain.pronunciation.service.impl;

import cn.adinall.ai.domain.pronunciation.adapter.port.IPronunciationPort;
import cn.adinall.ai.domain.pronunciation.adapter.repository.IPronunciationAssessmentRepository;
import cn.adinall.ai.domain.pronunciation.model.aggregate.PronunciationAssessmentAggregate;
import cn.adinall.ai.domain.pronunciation.model.entity.AssessPronunciationCommandEntity;
import cn.adinall.ai.domain.pronunciation.model.valobj.PronunciationResultVO;
import cn.adinall.ai.domain.pronunciation.service.IPronunciationService;
import cn.adinall.ai.types.enums.ResponseCodeEnum;
import cn.adinall.ai.types.exception.AppException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * 发音评测领域服务实现（校验 -&gt; 评测 -&gt; 落库）
 * <p>
 * 功能：把「一次跟读评测」跑完并留下记录；入参该挡的一次挡完，别让明显非法的请求占用一次上游推理。
 * <pre>
 *   assess()            校验音频与题面 -> port.assess -> 记耗时 -> 建聚合 -> save 回填 id
 *   listBySessionId()   直接委托仓储
 *   listByTurnId()      直接委托仓储
 * </pre>
 * 运行过程（一次跟读评测的完整路径）：
 * <pre>
 *   PronunciationCaseImpl.assess(sessionId, expectedText, lang, turnId, filename, audio)
 *        |
 *        v
 *   assess(sessionId, turnId, command)
 *        |
 *        | (1) requireAudio(command)      空音频 -> 400 invalid_request
 *        |     requireExpectedText(command) 空题面 -> 400 invalid_request
 *        |     语言兜底：command.lang() 为空 -> 不在这里补，交给适配器（它才知道上游默认语言）
 *        |
 *        | (2) t0 = System.currentTimeMillis()
 *        |     result = pronunciationPort.assess(command)
 *        |     latencyMs = System.currentTimeMillis() - t0
 *        |        |
 *        |        +--> 适配器抛 AppException（上游 4xx/5xx、连不上）-> 直接冒到全局处理器
 *        |        +--> 返回 result == null -> 抛 pronunciation_failed（502）
 *        |
 *        | (3) assessment = PronunciationAssessmentAggregate.create(
 *        |           sessionId, turnId, command.expectedText(), langOf(result), command.contentType(),
 *        |           command.audio().length, result, latencyMs)
 *        |
 *        | (4) assessmentRepository.save(assessment)   // 回填自增 id
 *        v
 *   带 assessmentId 的聚合
 * </pre>
 * 案例：
 * <pre>
 *   输入: sessionId="s-3f9c1a77e2b04d15", turnId=null,
 *         command{audio=37940B, filename="follow.wav", contentType="audio/wav",
 *                 expectedText="Hello, how are you?", lang="en"}
 *   处理: 校验通过 -> 上游 192ms 返回 score=86.67 -> INSERT -> id=1
 *   输出: PronunciationAssessmentAggregate{id=1, sessionId="s-3f9c1a77e2b04d15", turnId=null,
 *         expectedText="Hello, how are you?", lang="en", audioBytes=37940,
 *         result.score=86.67, latencyMs=192}
 *   非法输入: 音频 0 字节 -> 400 {"code":"invalid_request","message":"跟读音频为空，请重新录制"}
 *            题面空白   -> 400 {"code":"invalid_request","message":"题面（expectedText）不能为空"}
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>入参校验放在调用上游**之前**：上游要跑两个 Wav2Vec2 模型，把一次注定 400 的请求
 *       送过去是纯浪费（而且会占用上游的并发）。</li>
 *   <li>lang 的兜底不在本类做：默认语言是「上游配置」而不是「我方规则」，
 *       它属于适配器（配置里那一项）；本类只把上游回传的 language 记进聚合。</li>
 *   <li>落库用的语言取结果里的 language 而不是命令里的 lang：命令里的可能为空，
 *       结果里的是实际生效的那个，记录页要的是「这次到底用了什么语言」。</li>
 *   <li>不吞异常、不做重试：评测失败就让这次请求失败。上游 GPU 推理的重试成本高，
 *       而用户重录一次的代价很低 —— 这个取舍写在这里，避免以后有人加一层静默重试。</li>
 * </ul>
 */
@Service
public class PronunciationServiceImpl implements IPronunciationService {

    private static final Logger log = LoggerFactory.getLogger(PronunciationServiceImpl.class);

    private final IPronunciationPort pronunciationPort;
    private final IPronunciationAssessmentRepository assessmentRepository;

    public PronunciationServiceImpl(IPronunciationPort pronunciationPort,
                                    IPronunciationAssessmentRepository assessmentRepository) {
        this.pronunciationPort = pronunciationPort;
        this.assessmentRepository = assessmentRepository;
    }

    @Override
    public PronunciationAssessmentAggregate assess(String sessionId, Long turnId,
                                                   AssessPronunciationCommandEntity command) {
        if (command == null || !command.hasAudio()) {
            throw new AppException(ResponseCodeEnum.INVALID_REQUEST, "跟读音频为空，请重新录制");
        }
        if (command.expectedText() == null || command.expectedText().isBlank()) {
            throw new AppException(ResponseCodeEnum.INVALID_REQUEST, "题面（expectedText）不能为空");
        }

        long t0 = System.currentTimeMillis();
        PronunciationResultVO result = pronunciationPort.assess(command);
        long latencyMs = System.currentTimeMillis() - t0;

        if (result == null) {
            // 适配器正常情况下不会返回 null（读不到 score 时它抛 pronunciation_failed），
            // 这里再挡一次是为了不让一条「没有结论的评测」落进库。
            throw new AppException(ResponseCodeEnum.PRONUNCIATION_FAILED, "发音评测未返回结果");
        }

        // 落库用的语言以结果为准：命令里的 lang 可能是空的（走适配器兜底），
        // 结果里的 language 是上游实际用的那个。
        String effectiveLang = Optional.ofNullable(result.language())
                .filter(s -> !s.isBlank())
                .orElse(command.lang());

        PronunciationAssessmentAggregate assessment = PronunciationAssessmentAggregate.create(
                sessionId, turnId, command.expectedText(), effectiveLang, command.contentType(),
                command.audio().length, result, latencyMs);
        assessmentRepository.save(assessment);

        log.info("跟读评测完成 session={} turnId={} assessmentId={} score={} errors={} 耗时={}ms 音频={}",
                sessionId, turnId, assessment.getId(), result.score(), result.errorCount(),
                latencyMs, command.audioSummary());
        return assessment;
    }

    @Override
    public List<PronunciationAssessmentAggregate> listBySessionId(String sessionId) {
        return assessmentRepository.listBySessionId(sessionId);
    }

    @Override
    public List<PronunciationAssessmentAggregate> listByTurnId(Long turnId) {
        return assessmentRepository.listByTurnId(turnId);
    }
}

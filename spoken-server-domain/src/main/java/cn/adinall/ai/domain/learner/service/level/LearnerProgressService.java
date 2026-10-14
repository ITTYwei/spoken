package cn.adinall.ai.domain.learner.service.level;

import cn.adinall.ai.domain.learner.adapter.repository.ILearnerProgressRepository;
import cn.adinall.ai.domain.learner.model.valobj.*;
import org.springframework.stereotype.Service;
import org.slf4j.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * 功能：在新会话开始时按近期跨场证据最多提升一级，本场等级始终冻结。
 * <p>体系架构 / 运行过程：</p>
 * <pre>最近30天10场 -> 至少3场20回答 -> 80%支持下一档 -> CAS promotion -> freeze</pre>
 * <p>案例：两句高分 -> 保持 L2；三场20独立有效回答 -> 最多升到 L3。</p>
 * <p>设计要点：</p>
 * <ul><li>只计算已确认回答，拒绝和练习不降级。</li>
 * <li>新证据窗口从最近升级后重新积累，旧证据不能反复推动升级。</li></ul>
 */
@Service
public class LearnerProgressService {
    /** 能力证据及稳定等级仓储。 */ private final ILearnerProgressRepository repository;
    /** 记录降级原因，不打印用户全文。 */ private static final Logger log = LoggerFactory.getLogger(LearnerProgressService.class);
    public LearnerProgressService(ILearnerProgressRepository repository) { this.repository = repository; }
    public EnglishLevelEnum begin(String subject, String newSession, Instant now) {
        try {
            // 新会话优先读取手动选择；不覆盖自动等级，也不消耗历史升级证据。
            EnglishLevelEnum manual = repository.manualLevel(subject);
            if (manual != null) return manual;
            EnglishLevelEnum current = repository.level(subject);
            if (current == null) current = EnglishLevelEnum.L2;
            var evidence = repository.recent(subject, now.minus(30, ChronoUnit.DAYS));
            EnglishLevelEnum proposed = promotion(current, evidence);
            if (proposed != current && repository.promote(subject, current, proposed, newSession)) return proposed;
            return current;
        } catch (RuntimeException e) {
            log.warn("能力历史不可用，保守从L2开始 type={}", e.getClass().getSimpleName());
            return EnglishLevelEnum.L2;
        }
    }
    /** 读取真实设置状态；页面查询失败直接报错，不用默认值冒充已经保存的选择。 */
    public EnglishLevelSettingVO settings(String subject) {
        var automatic = repository.level(subject);
        if (automatic == null) automatic = EnglishLevelEnum.L2;
        var manual = repository.manualLevel(subject);
        return new EnglishLevelSettingVO(automatic, manual, manual == null ? automatic : manual);
    }
    /** 只写陪练难度选择；不改自动档位、不删除证据、不影响已开始的会话。 */
    public EnglishLevelSettingVO select(String subject, EnglishLevelEnum level) {
        repository.setManualLevel(subject, level);
        return settings(subject);
    }
    public void record(LearningEvidenceVO evidence) { repository.record(evidence); }
    /** 纯策略便于验证门槛；一场长对话不代替三场稳定表现。 */
    public static EnglishLevelEnum promotion(EnglishLevelEnum current, List<LearningEvidenceVO> source) {
        if (current == EnglishLevelEnum.L6) return current;
        var ids = source.stream().sorted(Comparator.comparing(LearningEvidenceVO::observedAt).reversed())
                .map(LearningEvidenceVO::sessionId).distinct().limit(10).toList();
        var valid = source.stream().filter(e -> ids.contains(e.sessionId()) && e.eligible()
                && "ANSWERED".equals(e.outcome()) && e.confidence() >= .85 && e.candidate() != null)
                .collect(java.util.stream.Collectors.toMap(LearningEvidenceVO::turnId, e -> e, (a,b) -> b)).values();
        if (valid.size() < 20 || valid.stream().map(LearningEvidenceVO::sessionId).distinct().count() < 3) return current;
        var next = EnglishLevelEnum.values()[current.ordinal()+1];
        // 最近三个有证据的会话都需多数支持；不让一场高分补偿另两场困难。
        var latest = valid.stream().sorted(Comparator.comparing(LearningEvidenceVO::observedAt).reversed())
                .map(LearningEvidenceVO::sessionId).distinct().limit(3).toList();
        for (String session : latest) {
            var one = valid.stream().filter(e -> session.equals(e.sessionId())).toList();
            if (one.stream().filter(e -> e.candidate().ordinal() >= next.ordinal()).count() < Math.ceil(one.size() * .8)) return current;
        }
        return valid.stream().filter(e -> e.candidate().ordinal() >= next.ordinal()).count() >= Math.ceil(valid.size() * .8)
                ? next : current;
    }
}

package cn.adinall.ai.domain.learning.service.level;

import cn.adinall.ai.domain.learning.model.valobj.EnglishLevelEnum;

import cn.adinall.ai.domain.learning.model.valobj.*;
import java.time.Instant;

/** 依据用户独立表达的语言证据校准模型提出的水平观察。 */
public final class LearningEvidencePolicy {
    /** 禁止实例化，仅通过静态方法使用此工具类。 */
    private LearningEvidencePolicy() { }
    /** 校准模型提出的能力候选档位，过滤低置信度、非独立表达和过短回答。 */
    public static LearningEvidenceVO evaluate(String subject, String session, Long turn, String text,
            String outcome, String candidateCode, double confidence, boolean independent, Instant observedAt) {
        int words = text == null || text.isBlank() ? 0 : text.trim().split("\\s+").length;
        EnglishLevelEnum candidate = EnglishLevelEnum.valueOf(candidateCode);
        boolean eligible = independent && "ANSWERED".equals(outcome) && confidence >= .85 && words >= 3;
        if (words < 8 || (text != null && LearnerLevelTracker.containsChinese(text))) {
            if (candidate.compareTo(EnglishLevelEnum.L3) > 0) candidate = EnglishLevelEnum.L3;
        } else if (words < 16 && candidate.compareTo(EnglishLevelEnum.L4) > 0) candidate = EnglishLevelEnum.L4;
        return new LearningEvidenceVO(subject, session, turn, outcome, candidate, confidence, eligible, observedAt);
    }
}

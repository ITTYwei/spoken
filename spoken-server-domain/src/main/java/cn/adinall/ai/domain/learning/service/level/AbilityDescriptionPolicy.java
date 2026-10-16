package cn.adinall.ai.domain.learning.service.level;

import cn.adinall.ai.domain.learning.model.valobj.LearningEvidenceVO;
import java.util.Collection;
import java.util.List;

import cn.adinall.ai.domain.learning.model.valobj.*;
import java.util.*;

/**
 * <b>功能</b>：根据跨会话的有效独立回答生成保守能力描述。
 * <p><b>体系架构 / 运行过程</b></p>
 * <pre>recent evidence -> eligible answers -> per-session lower quartile -> description</pre>
 * <p><b>案例</b>：只有单场 40 条答案 -> 正在了解；至少三场、20 条有效独立回答才展示能力。</p>
 * <b>设计要点</b>
 * <ul><li>展示规则归 Learning，不在页面用例中判定水平。</li>
 * <li>不修改自动等级；单场大量答案不能掩盖其他会话的困难。</li></ul>
 */
public final class AbilityDescriptionPolicy {
    /** 禁止实例化，仅通过静态方法使用此工具类。 */
    private AbilityDescriptionPolicy() {}
    /** 根据近期学习证据生成可追溯的能力描述。 */
    public static AbilityDescriptionVO describe(List<LearningEvidenceVO> source) {
        List<String> recent = source.stream().sorted(Comparator.comparing(LearningEvidenceVO::observedAt).reversed())
                .map(LearningEvidenceVO::sessionId).distinct().limit(10).toList();
        Collection<LearningEvidenceVO> valid = source.stream().filter(e -> recent.contains(e.sessionId()) && e.eligible() && "ANSWERED".equals(e.outcome())
                        && e.confidence() >= .85 && e.candidate() != null)
                .collect(java.util.stream.Collectors.toMap(LearningEvidenceVO::turnId, e -> e, (a, b) -> b)).values();
        if (valid.size() < 20 || valid.stream().map(LearningEvidenceVO::sessionId).distinct().count() < 3)
            return new AbilityDescriptionVO("learning", "正在了解");
        // 保守采用各场回答的低四分位，再取各场的最低结果；单场大量回答不能掩盖其他场的困难。
        int index = valid.stream().map(LearningEvidenceVO::sessionId).distinct().mapToInt(session -> {
            int[] levels = valid.stream().filter(e -> session.equals(e.sessionId())).mapToInt(e -> e.candidate().ordinal()).sorted().toArray();
            return levels[(levels.length - 1) / 4];
        }).min().orElse(1);
        String description = switch (EnglishLevelEnum.values()[index]) {
            case L1 -> "在提示帮助下，可以用单词或短语表达简单意思。";
            case L2 -> "在适当中文支持下，可以用短句回应日常问题。";
            case L3 -> "可以独立回答简单生活问题，表达日常需求。";
            case L4 -> "可以连续表达日常想法，并理解简单追问。";
            case L5 -> "可以解释原因、描述经历，并进行自然的日常交流。";
            case L6 -> "可以较完整地表达观点，并讨论较复杂的话题。";
        };
        return new AbilityDescriptionVO("ready", description);
    }

}

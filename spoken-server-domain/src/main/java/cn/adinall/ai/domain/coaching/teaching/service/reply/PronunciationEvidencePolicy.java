package cn.adinall.ai.domain.coaching.teaching.service.reply;

import cn.adinall.ai.domain.coaching.teaching.model.valobj.PronunciationFeedbackVO.PhoneVO;

import cn.adinall.ai.domain.coaching.teaching.model.valobj.PronunciationFeedbackVO;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * 从评测服务原始错词与音素结构筛选一条可信指导线索，不做综合能力评分。
 * 词级置信度缺失时允许使用该词下高置信度的具体音素差异；都缺失则不宣称某个音读错。
 * 忽略非目标词、无差异、非法置信度及超长字段，避免无关或异常上游结果进入教学提示。
 */
public final class PronunciationEvidencePolicy {
    /** 禁止实例化，仅通过静态方法使用此工具类。 */
    private PronunciationEvidencePolicy() { }

    /** 返回目标中的第一个可信问题，空表示不能提出具体纠音，不等同于发音没有问题。 */
    public static PronunciationFeedbackVO.IssueVO credibleIssue(String target, PronunciationFeedbackVO feedback) {
        if (feedback == null || target == null) return null;
        List<String> words = List.of(target.toLowerCase(Locale.ROOT).replaceAll("[^a-z'-]+", " ").trim().split("\\s+"));
        return feedback.errors().stream().filter(e -> e.word() != null && e.word().matches("[A-Za-z'-]{1,40}")
                && words.contains(e.word().toLowerCase(Locale.ROOT)) && valid(e.expected()) && valid(e.actual())
                && !e.expected().isBlank() && !Objects.equals(e.expected(), e.actual()) && (confident(e.confidence())
                || e.phones().stream().anyMatch(p -> valid(p.expected()) && p.heard() != null && p.heard().length() <= 80
                && !Objects.equals(p.expected(), p.heard()) && confident(p.confidence())))).findFirst().orElse(null);
    }

    /** 生成供 Agent 使用的真实线索；无实听音表示漏音，不用“你错了”的判断语气。 */
    public static String hint(PronunciationFeedbackVO.IssueVO issue) {
        PhoneVO phone = issue.phones().stream().filter(p -> valid(p.expected()) && p.heard() != null
                && p.heard().length() <= 80 && !Objects.equals(p.expected(), p.heard()) && confident(p.confidence())).findFirst().orElse(null);
        String expected = phone == null ? issue.expected() : phone.expected();
        String actual = phone == null ? issue.actual() : phone.heard();
        return "可以把 " + issue.word() + " 中的 /" + expected + "/ 示范得更清楚。评测实际听到"
                + (actual == null || actual.isBlank() ? "这个音尚不明显" : " /" + actual + "/") + "，只指导这个细节。";
    }

    /** 判断音素证据是否包含可用于纠音的有效内容。 */
    private static boolean valid(String value) { return value != null && value.length() <= 160; }
    /** 判断发音证据的置信度是否达到可用门槛。 */
    private static boolean confident(Double value) { return value != null && Double.isFinite(value) && value >= .8 && value <= 1; }
}

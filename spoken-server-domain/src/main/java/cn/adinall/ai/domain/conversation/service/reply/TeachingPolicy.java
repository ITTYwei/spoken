package cn.adinall.ai.domain.conversation.service.reply;

import cn.adinall.ai.domain.conversation.model.valobj.*;
import cn.adinall.ai.domain.learner.model.valobj.EnglishLevelEnum;
import cn.adinall.ai.domain.learner.service.level.LearnerLevelTracker;
import java.util.List;
import java.util.Locale;

/**
 * 对话领域的教学参考策略，只整理服务端已知事实和可信跟读证据，不替模型决定用户意图。
 * 流程：空输入提示 → 核查实际跟读尝试 → 整理评测证据 → 交给 Agent 理解原话并回应。
 * 普通中文、低等级和“不会说”都不再强制触发跟读；“我想学音标”由模型直接处理。
 * 保留硬边界：没有实际目标尝试就不纠音；练习素材不作为偏好；同一目标最多建议重试两次。
 */
public final class TeachingPolicy {
    /** 同一句连续跟读最多建议两次纠音重试；首次示范不计入，换句后重新计数。 */
    public static final int MAX_READING_RETRIES = 2;
    private TeachingPolicy() { }

    /**
     * 输入会话档位、上一已播任务、本轮原话及可选评测，输出教学建议和证据性质。
     * 无随机抽样、无仓储写入；实际教学形式由 Agent 结合当前请求选择。
     */
    public static TeachingDecisionVO decide(EnglishLevelEnum level, TeachingTaskVO previous,
                                            String text, PronunciationFeedbackVO feedback, boolean voice) {
        String input = text == null ? "" : text.trim();
        boolean chinese = level.ordinal() <= EnglishLevelEnum.L2.ordinal() || LearnerLevelTracker.containsChinese(input);
        if (input.isBlank()) return result(level, TeachingTaskVO.response(), TeachingOutcomeEnum.NO_AUDIO, true, "", false, false);
        boolean reading = previous != null && previous.reading() && voice;
        if (!reading || !ReadingAttemptPolicy.isAttempt(previous.target(), input)) {
            // 上轮要求跟读不代表本轮仍在跟读；用户可以改问知识或提出新的学习需求。
            return result(level, TeachingTaskVO.response(), reading ? TeachingOutcomeEnum.READ_NOT_ATTEMPTED : TeachingOutcomeEnum.RESPONDED,
                    chinese, "", false, false);
        }
        boolean usable = feedback != null && feedback.score() != null && Double.isFinite(feedback.score())
                && feedback.score() >= 0 && feedback.score() <= 100;
        var issue = usable && feedback.errors() != null ? feedback.errors().stream()
                .filter(e -> e.word() != null && e.word().matches("[A-Za-z'-]{1,40}")
                        && List.of(previous.target().toLowerCase(Locale.ROOT).replaceAll("[^a-z'-]+", " ").trim().split("\\s+")).contains(e.word().toLowerCase(Locale.ROOT))
                        && e.expected() != null && !e.expected().isBlank() && !java.util.Objects.equals(e.expected(), e.actual())
                        && e.confidence() != null && Double.isFinite(e.confidence()) && e.confidence() >= .8 && e.confidence() <= 1)
                .findFirst().orElse(null) : null;
        if (usable && feedback.score() >= 80 && issue == null)
            return result(level, TeachingTaskVO.response(), TeachingOutcomeEnum.READ_PASS, chinese, "", true, true);
        if (issue != null && previous.attempts() < MAX_READING_RETRIES) {
            // 高总分不能遮蔽具体可信错词；只是建议指导一个细节，不强制用户继续重读。
            String hint = "可示范单词 " + issue.word() + " 的读法。";
            if (issue.word().toLowerCase(Locale.ROOT).endsWith("ed") && issue.expected().endsWith("t"))
                hint = issue.word() + " 以短促的 /t/ 音结尾，不要再加一个音节。";
            return result(level, new TeachingTaskVO(TeachingActionEnum.CORRECT.code(), previous.target(), "", previous.attempts() + 1),
                    TeachingOutcomeEnum.READ_RETRY, true, hint, true, true);
        }
        return result(level, TeachingTaskVO.response(), usable ? TeachingOutcomeEnum.READ_UNCERTAIN : TeachingOutcomeEnum.NO_EVIDENCE,
                chinese, "", false, true);
    }

    /**
     * 兼容旧测试和调用签名；draw 仅检查旧参数合法性，不再影响是否跟读。
     * 待所有调用迁移后可删除本重载，避免一次修改扩大到无关接口。
     */
    @Deprecated
    public static TeachingDecisionVO decide(EnglishLevelEnum level, TeachingTaskVO previous,
                                            String text, PronunciationFeedbackVO feedback, boolean voice, double draw) {
        if (draw < 0 || draw >= 1 || !Double.isFinite(draw)) throw new IllegalArgumentException("draw must be in [0,1)");
        return decide(level, previous, text, feedback, voice);
    }

    /** 统一封装事实与建议，不生成台词，不改变稳定等级。 */
    private static TeachingDecisionVO result(EnglishLevelEnum level, TeachingTaskVO task, TeachingOutcomeEnum outcome,
                                             boolean chinese, String hint, boolean reliable, boolean practice) {
        return new TeachingDecisionVO(level, task, outcome, chinese, hint, reliable, practice);
    }
}

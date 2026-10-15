package cn.adinall.ai.domain.conversation.service.reply;

import cn.adinall.ai.domain.conversation.model.valobj.*;
import cn.adinall.ai.domain.learner.model.valobj.EnglishLevelEnum;
import java.util.List;

/**
 * 情境跟读领域规则：控制评测准入及三次纠音上限，不访问仓储或模型。
 * 英文句子、译意和情境说明全部由现有模型按真实等级及上下文生成，此处不保存题库。
 * Agent 负责自然讲解，服务端核验并保存实际播出的唯一英文目标；不依赖模型数次数或从历史猜题。
 * 与普通聊天的建议策略分开：只有用户主动打开跟读才使用此处规则。
 */
public final class GuidedReadingPolicy {
    /** 首次示范不计数；可指导重读三次，第三次尝试后仍失败也进入下一句。 */
    public static final int MAX_CORRECTIONS = 3;
    /** 评测原始总分的过关线；用于本练习进度，不修改综合英语水平。 */
    public static final double PASS_SCORE = 80;

    private GuidedReadingPolicy() { }

    /**
     * 跟读模式允许不完整、误识别的英文尝试；明确提问、暂停和新需求仍不按旧目标评测。
     * 精确目标优先，以免 How are you? 这类问句素材被误当问题。
     * 这里仅限定评测准入，普通问题的具体回答继续由 Agent 理解。
     */
    public static boolean isAttempt(String target, String text) {
        if (ReadingAttemptPolicy.isAttempt(target, text)) return true;
        // 目标问句只读了一部分也可能是尝试；出现解释、词义等新请求时仍交给 Agent。
        if (target != null && text != null && !text.matches("(?s).*\\p{IsHan}.*")
                && !text.toLowerCase(java.util.Locale.ROOT).matches("(?s).*\\b(?:mean|means|meaning|explain|pronounce|phonetics|grammar|translate)\\b.*")) {
            var expected = new java.util.HashSet<>(java.util.List.of(target.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z]+", " ").trim().split("\\s+")));
            var heard = new java.util.HashSet<>(java.util.List.of(text.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z]+", " ").trim().split("\\s+")));
            if (!heard.isEmpty() && expected.containsAll(heard) && heard.stream().noneMatch(String::isBlank)) return true;
        }
        if (text == null || text.isBlank() || text.matches("(?s).*\\p{IsHan}.*")) return false;
        String input = text.trim().toLowerCase(java.util.Locale.ROOT);
        if (input.matches("(?s).*\\b(?:meaning|explain|pronounce|phonetics|grammar|translate)\\b.*")) return false;
        if (input.matches("(?s)^(?:please |can you |could you )?(?:stop|pause|skip|explain|teach|help|translate|tell me).*")) return false;
        if (input.matches("(?s)^(?:i (?:want|would like)|i'd like|what|why|how|when|where|who|which|can you|could you|do you|is it|are you)\\b.*")) return false;
        return input.matches("(?s).*[a-z].*");
    }

    /**
     * 根据真实结构化结果冻结本轮任务：合格/三次用完则换句；有可信错词才具体纠音。
     * 缺评测不能宣称不合格或通过；保留原句让用户重试，不消耗纠音配额。
     */
    public static TeachingDecisionVO decide(EnglishLevelEnum level, TeachingTaskVO previous, String text,
                                             PronunciationFeedbackVO feedback, boolean voice) {
        if (previous == null || !previous.reading() || !voice || !isAttempt(previous.target(), text))
            return TeachingPolicy.decide(level, previous, text, null, false);
        boolean usable = feedback != null && feedback.score() != null && Double.isFinite(feedback.score())
                && feedback.score() >= 0 && feedback.score() <= 100;
        var issue = usable ? PronunciationEvidencePolicy.credibleIssue(previous.target(), feedback) : null;
        boolean pass = usable && feedback.score() >= PASS_SCORE && issue == null;
        if (pass || previous.attempts() >= MAX_CORRECTIONS) {
            // 空目标表示请求模型生成新句，绝不能拿旧目标或固定例句补齐。
            return new TeachingDecisionVO(level, new TeachingTaskVO(TeachingActionEnum.READ.code(), "", "", 0),
                    pass ? TeachingOutcomeEnum.READ_PASS : TeachingOutcomeEnum.READ_LIMIT_REACHED, true,
                    "", usable, true);
        }
        String hint = issue == null ? "" : PronunciationEvidencePolicy.hint(issue);
        return new TeachingDecisionVO(level, new TeachingTaskVO(usable ? TeachingActionEnum.CORRECT.code() : TeachingActionEnum.READ.code(),
                previous.target(), "", previous.attempts() + (usable ? 1 : 0)),
                usable ? TeachingOutcomeEnum.READ_RETRY : TeachingOutcomeEnum.NO_EVIDENCE,
                true, hint, usable, true);
    }

    /**
     * 情境模式先核验整段再播报：新句必须由模型生成且不重复近期目标；重试必须保留原句。
     * 无效结果显式失败，由 Case 限定重生成次数；没有固定教学内容的降级题库。
     * @param recent 本轮冻结的近期已播目标，最多八句，不依赖可裁剪聊天历史
     */
    public static reactor.core.publisher.Flux<String> guard(reactor.core.publisher.Flux<String> source,
                                                           TeachingDecisionVO decision, List<String> recent) {
        if (!decision.nextTask().reading()) return source;
        // 每次重生成都重新分配缓冲，不能把被拒绝的旧草稿拼进下一次结果。
        return reactor.core.publisher.Flux.defer(() -> source.reduce(new StringBuilder(), (all, delta) -> {
            if (all.length() + delta.length() > 32768) throw new IllegalStateException("reply_too_large");
            return all.append(delta);
        }).map(all -> {
            String spoken = all.toString();
            var actual = SpokenTaskResolver.resolveGuided(decision.nextTask(), spoken);
            String planned = decision.nextTask().target();
            boolean newSentence = planned == null || planned.isBlank();
            if (newSentence && recent.stream().anyMatch(old -> sameTarget(old, actual.target())))
                throw new ReadingGenerationException(ReadingGenerationErrorEnum.RECENT_DUPLICATE);
            if (!newSentence && !sameTarget(planned, actual.target()))
                throw new ReadingGenerationException(ReadingGenerationErrorEnum.TARGET_CHANGED);
            // 引号外必须有教学交流，避免仅输出待跟读英文。L1/L2 还必须含中文支援。
            String narration = spoken.replaceAll("\"[^\"]*\"|“[^”]*”|‘[^’]*’", "").trim();
            if (narration.length() < 8)
                throw new ReadingGenerationException(ReadingGenerationErrorEnum.MISSING_EXPLANATION);
            if (decision.level().ordinal() <= EnglishLevelEnum.L2.ordinal() && !narration.matches("(?s).*\\p{IsHan}.*"))
                throw new ReadingGenerationException(ReadingGenerationErrorEnum.MISSING_CHINESE);
            // 没有具体证据时只允许通用重读邀请；不信任模型凭空编造的音素指导。
            if (decision.outcome() == TeachingOutcomeEnum.READ_RETRY && decision.correctionHint().isBlank())
                return "我们把这句再连贯地读一次：“" + actual.target() + "”。你来试着读一读吧。";
            if (decision.outcome() == TeachingOutcomeEnum.NO_EVIDENCE)
                return "这次暂时没有拿到可用的评测结果，我们再试一次：“" + actual.target() + "”。你来试着读一读吧。";
            return SpokenTaskResolver.ensureReadingInvitation(spoken);
        }).flux());
    }

    /** 同一句换大小写、标点或空格不算新题；不把同义的新表达粗暴归并。 */
    public static boolean sameTarget(String left, String right) { return normalize(left).equals(normalize(right)); }
    private static String normalize(String text) {
        return text == null ? "" : text.toLowerCase(java.util.Locale.ROOT).replace('’', '\'')
                .replaceAll("[^a-z0-9']+", " ").trim();
    }
}

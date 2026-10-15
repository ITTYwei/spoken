package cn.adinall.ai.domain.conversation.service.reply;

import cn.adinall.ai.domain.conversation.model.valobj.*;
import cn.adinall.ai.domain.learner.model.valobj.EnglishLevelEnum;
import java.util.List;

/**
 * 情境跟读领域规则：控制级别、连续句组、评测准入及三次纠音上限，不访问仓储或模型。
 * 首版使用原创日常见面句组，各档内容按认识、近况、兴趣、邀约连续展开。
 * Agent 负责自然讲解，服务端确定唯一英文目标；不依赖模型数次数或从历史猜题。
 * 与普通聊天的建议策略分开：只有用户主动打开跟读才使用此处规则。
 */
public final class GuidedReadingPolicy {
    /** 首次示范不计数；可指导重读三次，第三次尝试后仍失败也进入下一句。 */
    public static final int MAX_CORRECTIONS = 3;
    /** 评测原始总分的过关线；用于本练习进度，不修改综合英语水平。 */
    public static final double PASS_SCORE = 80;

    /** 原创单句及用途、中文意思；用途用于保持聊天形式，不只是朗读题面。 */
    public record Sentence(String english, String meaning, String situation) {
        /** 缺少可用模型输出时的完整教学示范，只将英文加引号。 */
        public String demonstration() {
            return situation + "，可以说：“" + english + "”，意思是“" + meaning + "”。你来试着读一读吧。";
        }
    }

    private GuidedReadingPolicy() { }

    /** 按真实档位选择句组；每组从见面延伸到告别，完成一组后可重新练习。 */
    public static List<Sentence> sentences(EnglishLevelEnum level) {
        return switch (level) {
            case L1 -> List.of(s("Hello!", "你好", "见到朋友时打个招呼"), s("I'm fine.", "我很好", "朋友问起你的近况"),
                    s("My name is Sam.", "我叫山姆", "接着介绍自己的名字"), s("I like books.", "我喜欢书", "聊到自己的兴趣"),
                    s("Let's go!", "我们走吧", "想邀请朋友一起走"), s("See you!", "再见", "最后和朋友道别"));
            case L2 -> List.of(s("How are you?", "你好吗", "想问朋友最近怎么样"), s("I'm doing well, thanks.", "我很好，谢谢", "朋友也问起你的近况"),
                    s("What do you like to do?", "你喜欢做什么", "接着了解朋友的兴趣"), s("I like reading books.", "我喜欢读书", "轮到你分享爱好"),
                    s("Would you like some coffee?", "你想喝点咖啡吗", "想边喝咖啡边聊"), s("See you next time!", "下次见", "聊天结束时期待再见"));
            case L3 -> List.of(s("How has your day been?", "你今天过得怎么样", "傍晚遇到朋友"), s("It's been busy, but pretty good.", "挺忙的，不过还不错", "朋友问你今天怎么样"),
                    s("I've been reading a new book.", "我最近在读一本新书", "接着分享最近的生活"), s("What kind of books do you enjoy?", "你喜欢哪类书", "想了解朋友的阅读喜好"),
                    s("Shall we visit the bookshop together?", "我们一起去书店好吗", "想把共同兴趣变成邀约"), s("Let's catch up again soon.", "我们改天再聊", "道别时约好再联系"));
            case L4 -> List.of(s("It's been a while. How have you been?", "好久不见，你最近怎么样", "偶遇许久未见的朋友"), s("I've been keeping busy with a new project.", "我最近在忙一个新项目", "接着分享近况"),
                    s("Reading helps me unwind after a long day.", "忙碌一天后读书能让我放松", "话题转到下班后的爱好"), s("Have you read anything interesting lately?", "你最近读过什么有趣的书吗", "邀请朋友分享阅读经历"),
                    s("We should check out that new bookshop sometime.", "我们找时间去看看那家新书店吧", "顺着阅读话题发出邀请"), s("It was lovely catching up with you.", "很高兴和你聊聊近况", "结束这次轻松的见面"));
            case L5 -> List.of(s("What have you been up to since we last met?", "上次见面后你都在忙什么", "和老朋友重逢"), s("I've taken up reading as a way to switch off.", "我开始通过阅读放松下来", "分享自己新培养的习惯"),
                    s("The story gave me a fresh perspective on friendship.", "这个故事让我对友谊有了新的看法", "进一步介绍一本书带来的感受"), s("Is there a book you'd recommend without hesitation?", "有没有一本你会毫不犹豫推荐的书", "邀请朋友推荐读物"),
                    s("Why don't we swap recommendations over coffee?", "不如我们喝咖啡时交换书单", "自然提出下一次见面"), s("I'll look forward to hearing what you think.", "我很期待听听你的想法", "为下次交流留下话题"));
            case L6 -> List.of(s("How has life been treating you since we last caught up?", "上次聊过之后你过得怎么样", "和老朋友自然地寒暄"), s("I've been trying to strike a better balance between work and leisure.", "我一直在努力更好地平衡工作与休闲", "深入分享近期生活变化"),
                    s("A novel I read recently challenged some of my assumptions.", "最近读的一部小说挑战了我的一些固有看法", "话题延伸到阅读和思考"), s("What struck you most about the author's perspective?", "作者的视角最让你印象深刻的是什么", "围绕共同读物展开讨论"),
                    s("Perhaps we could turn these conversations into a regular book club.", "也许我们可以把这样的交流变成定期读书会", "把共同兴趣变成持续的约定"), s("Let's pick this up next time once we've both finished the book.", "等我们都读完这本书，下次接着聊", "为下次见面自然收尾"));
        };
    }

    private static Sentence s(String english, String meaning, String situation) { return new Sentence(english, meaning, situation); }

    /** 只取当前目标的后一句；重试返回原句。索引保存在会话聚合，播报完成后才前进。 */
    public static Sentence sentence(EnglishLevelEnum level, int index) {
        var group = sentences(level);
        return group.get(Math.floorMod(index, group.size()));
    }

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
                                             PronunciationFeedbackVO feedback, boolean voice, int index) {
        if (previous == null || !previous.reading() || !voice || !isAttempt(previous.target(), text))
            return TeachingPolicy.decide(level, previous, text, null, false);
        boolean usable = feedback != null && feedback.score() != null && Double.isFinite(feedback.score())
                && feedback.score() >= 0 && feedback.score() <= 100;
        var issue = usable ? PronunciationEvidencePolicy.credibleIssue(previous.target(), feedback) : null;
        boolean pass = usable && feedback.score() >= PASS_SCORE && issue == null;
        if (pass || previous.attempts() >= MAX_CORRECTIONS) {
            var next = sentence(level, index + 1);
            return new TeachingDecisionVO(level, new TeachingTaskVO(TeachingActionEnum.READ.code(), next.english(), "", 0),
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
     * 情境模式必须在播报前核验整段，防止模型换错目标或超三次继续旧句。
     * 只在确有练习计划时启用；知识讲解仍走原流式链路，不截断用户问题的答案。
     */
    public static reactor.core.publisher.Flux<String> guard(reactor.core.publisher.Flux<String> source,
                                                           TeachingDecisionVO decision, int index) {
        if (!decision.nextTask().reading()) return source;
        return source.reduce(new StringBuilder(), (all, delta) -> {
            if (all.length() + delta.length() > 32768) throw new IllegalStateException("reply_too_large");
            return all.append(delta);
        }).map(all -> {
            String spoken = all.toString();
            var actual = SpokenTaskResolver.resolve(TeachingTaskVO.response(), spoken);
            // 没有可信错词时使用不含具体纠错的示范，避免模型凭空猜测发音问题。
            if (decision.outcome() != TeachingOutcomeEnum.READ_LIMIT_REACHED && actual.reading() && actual.target().equals(decision.nextTask().target())
                    && (decision.outcome() != TeachingOutcomeEnum.READ_RETRY || !decision.correctionHint().isBlank())) return spoken;
            var next = sentence(decision.level(), index);
            if (!next.english().equals(decision.nextTask().target())) next = sentence(decision.level(), index + 1);
            String prefix = switch (decision.outcome()) {
                case READ_PASS -> "这句读得很清楚，我们接着聊。";
                case READ_LIMIT_REACHED -> "这句先练到这里，我们换一句。";
                case NO_EVIDENCE -> "这次暂时没有拿到可用的评测结果，我们再试一次。";
                default -> decision.correctionHint().isBlank() ? "我们把这句再连贯地读一次。" : decision.correctionHint();
            };
            return prefix + next.demonstration();
        }).flux();
    }
}

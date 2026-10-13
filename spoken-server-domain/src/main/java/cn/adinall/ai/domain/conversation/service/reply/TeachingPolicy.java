package cn.adinall.ai.domain.conversation.service.reply;

import cn.adinall.ai.domain.conversation.model.valobj.*;
import cn.adinall.ai.domain.learner.model.valobj.EnglishLevelEnum;
import java.util.Locale;

/**
 * 功能：按上一任务和本轮明确证据安排一个动作，拒绝与困难分别处理。
 * <p>体系架构 / 运行过程：</p>
 * <pre>no audio -> decline -> help -> fixed-target reading -> normal question
 * reading: reliable issue -> one correction -> at most two tries -> easier task</pre>
 * <p>案例：上一轮 READ，用户“不想读” -> “不想读也没关系…？”；不再次跟读。</p>
 * <p>设计要点：</p>
 * <ul><li>总分只作分区参考；自由回答评测不能触发纠音。</li>
 * <li>固定教学句保证不同时要求读和回答。</li><li>没有证据时不判用户不会。</li></ul>
 */
public final class TeachingPolicy {
    private TeachingPolicy() {}
    public static TeachingDecisionVO decide(EnglishLevelEnum level, TeachingTaskVO previous,
                                             String text, PronunciationFeedbackVO feedback, boolean voice) {
        String input = text == null ? "" : text.trim();
        String lower = input.toLowerCase(Locale.ROOT);
        boolean reading = previous != null && previous.reading();
        boolean chinese = level.ordinal() <= EnglishLevelEnum.L3.ordinal();
        if (input.isBlank()) return result(level, TeachingTaskVO.question(""), "NO_AUDIO", true,
                "不用急，这个任务可以先放一放。你想换个话题吗？", false, reading);
        if (matches(lower, "不想", "不愿", "不聊", "跳过", "不读", "换个话题", "skip", "don't want", "do not want", "rather not")) {
            return result(level, TeachingTaskVO.question(""), "DECLINED", chinese,
                    chinese ? (reading ? "不想读也没关系，我们接着聊。你今天吃了什么？" : "这个话题可以先放一放。你平时喜欢吃什么？")
                            : "We can leave that for now. What food do you enjoy?", false, reading);
        }
        if (matches(lower, "不知道", "不清楚", "听不懂", "看不懂", "用中文", "说中文", "don't know", "do not know", "don't understand", "do not understand")) {
            return result(level, TeachingTaskVO.question(""), "HELP", true,
                    "没关系，这个问题可以先放一放。我们聊简单一点，你今天吃了什么？", false, reading);
        }
        if (matches(lower, "不会说", "不会读", "how do i say", "can't say", "cannot say")) {
            String target = reading && previous.attempts() < 2 ? shorten(previous.target()) : starter(previous == null ? "" : previous.question());
            return result(level, new TeachingTaskVO("READ", target, "", reading ? previous.attempts() + 1 : 0),
                    "HELP", true, "没关系，我们先说简单一点。听我说：" + target + " 你试着读这一句。", false, reading);
        }
        if (reading && voice) {
            boolean usable = feedback != null && feedback.score() != null && Double.isFinite(feedback.score())
                    && feedback.score() >= 0 && feedback.score() <= 100;
            var issue = usable && feedback.errors() != null ? feedback.errors().stream()
                    .filter(e -> e.word() != null && e.word().matches("[A-Za-z'-]{1,40}")
                            && previous.target().toLowerCase(Locale.ROOT).contains(e.word().toLowerCase(Locale.ROOT))
                            && e.expected()!=null && !e.expected().isBlank() && !java.util.Objects.equals(e.expected(),e.actual())
                            && e.confidence() != null && Double.isFinite(e.confidence()) && e.confidence() >= .8 && e.confidence() <= 1).findFirst().orElse(null) : null;
            if (usable && feedback.score() >= 80 && issue == null) {
                return result(level, TeachingTaskVO.question(""), "READ_PASS", chinese, "", true, true);
            }
            if (usable && issue != null && feedback.score() < 80 && previous.attempts() < 2) {
                String word = issue.word();
                String hint = "我们把 " + word + " 单独慢读，再接回句子里。";
                if (word.toLowerCase(Locale.ROOT).endsWith("ed") && issue.expected() != null && issue.expected().endsWith("t"))
                    hint = word + " 末尾有一个轻短的 t 音，不要多加一个音节。";
                return result(level, new TeachingTaskVO("CORRECT", previous.target(), "", previous.attempts()+1),
                        "READ_RETRY", true, hint + "听我说：" + previous.target() + " 你再试一次。", true, true);
            }
            // 低分但没有可信错词，不编造发音问题；两次后退出原目标。
            return result(level, TeachingTaskVO.question(""), usable ? "READ_UNCERTAIN" : "NO_EVIDENCE", true,
                    "这句可以先放一放，我们换个轻松的话题。你平时喜欢听什么音乐？", false, true);
        }
        // 打字不能证明读音；只记录回应，不把语义相关性或能力判断交给词数。
        return result(level, TeachingTaskVO.question(""), "RESPONDED", chinese, "", false, reading);
    }
    private static TeachingDecisionVO result(EnglishLevelEnum level, TeachingTaskVO task, String outcome,
                                             boolean chinese, String script, boolean reliable, boolean practice) {
        return new TeachingDecisionVO(level, task, outcome, chinese, script, reliable, practice);
    }
    private static boolean matches(String text, String... hints) {
        for (String hint : hints) if (text.contains(hint)) return true;
        return false;
    }
    private static String shorten(String target) {
        if (target == null || target.isBlank()) return "good morning";
        var words = target.trim().split("\\s+");
        return String.join(" ", java.util.Arrays.copyOf(words, Math.min(words.length, 3)));
    }
    /** 帮助表达沿用当前话题；这些是练习示例，不是用户事实。 */
    private static String starter(String question) {
        String q=question==null?"":question.toLowerCase(Locale.ROOT);
        if(q.contains("咖啡") || q.contains("拿铁") || q.contains("coffee")) return "I like coffee.";
        if(q.contains("吃") || q.contains("菜") || q.contains("food")) return "I like noodles.";
        if(q.contains("公园") || q.contains("park")) return "I like walking.";
        return "I like music.";
    }
}

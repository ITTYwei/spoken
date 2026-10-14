package cn.adinall.ai.domain.conversation.service.reply;

import cn.adinall.ai.domain.conversation.model.valobj.TeachingTaskVO;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 功能：按数字人实际播报内容提交待完成任务，不把模型未说出的跟读指令记到会话里。
 * <p>体系架构 / 运行过程：</p>
 * <pre>planned task + completed spoken text -> verify target and invitation -> committed task</pre>
 * <p>案例：计划 READ("Let's talk.")，实际只问“今天聊什么？” -> 提交 QUESTION。</p>
 * <p>设计要点：</p>
 * <ul><li>同一规则用于流式和整段播报的完成回调。</li>
 * <li>无法确认用户听到了跟读要求时，不保留 READ/CORRECT 状态。</li></ul>
 */
public final class SpokenTaskResolver {
    private static final Pattern QUESTION = Pattern.compile("[^.!?。！？]*[?？]");
    /** 模型新生成的唯一英文跟读句，以引号界定，避免把中文解释当成目标。 */
    private static final Pattern TARGET = Pattern.compile("[\"“]([A-Za-z][A-Za-z\\s’',.!?-]{0,179})[\"”]");

    private SpokenTaskResolver() {
    }

    /** 已播报文本同时含完整目标和跟读邀请时，才提交原跟读任务。 */
    /* 例：“How are you?” 意思是你好吗。请跟读。
     * 引号中的问号不是向用户提问，必须提交 READ；否则下一轮会被误当普通问答。
     * 若模型只提到英文句、没有邀请跟读，则不会凭计划强行认定用户应该读它。
     */
    public static TeachingTaskVO resolve(TeachingTaskVO planned, String spoken) {
        // 新跟读只由模型生成目标，播报完成后再保存实际说出的英文句。
        if (planned != null && "READ".equals(planned.action()) && (planned.target() == null || planned.target().isBlank()) && spoken != null) {
            var matcher = TARGET.matcher(spoken);
            if (matcher.find()) {
                String target = matcher.group(1).trim();
                if (!matcher.find() && readingWasSpoken(spoken, target))
                    return new TeachingTaskVO("READ", target, "", 0);
            }
        }
        if (planned != null && planned.reading() && readingWasSpoken(spoken, planned.target())) return planned;
        return TeachingTaskVO.question(lastQuestion(spoken));
    }

    public static String lastQuestion(String spoken) {
        if (spoken == null) return "";
        // 引号中的问句可能是跟读目标或中文译文，不能被提升为真实聊天问题。
        // 例如 “How are you?” 意思是你好吗。请跟读。必须仍提交 READ，下一轮才有练习标记。
        String questions = spoken.replaceAll("\"[^\"]*\"|“[^”]*”", "");
        // 英文问句后解释“意思是：你准备好了吗？”仍是译意，不是下一轮等待回答的中文问题。
        // 只消除带明确译意前缀的中文段；真实未加前缀的中文聊天问题仍可被正常提交。
        questions = questions.replaceAll("(?:这句英文的意思是|这句话的意思是|中文意思是|意思是|中文是|也就是|是在问|表示)[：:\\s]*[\\p{IsHan}，、\\s]+[。！？.!?]?", "");
        var matcher = QUESTION.matcher(questions);
        String last = "";
        while (matcher.find()) last = matcher.group().trim();
        return last;
    }

    private static boolean readingWasSpoken(String spoken, String target) {
        if (spoken == null || target == null || target.isBlank()) return false;
        String line = spoken.toLowerCase(Locale.ROOT).replace('’', '\'').replaceAll("\\s+", " ");
        String phrase = target.toLowerCase(Locale.ROOT).replace('’', '\'')
                .replaceAll("[.!?。！？]+$", "").replaceAll("\\s+", " ").trim();
        if (phrase.isEmpty() || !line.contains(phrase)) return false;
        // 模型只提到目标句还不算下达任务；另问一个聊天问题也不能记为跟读。
        String question = lastQuestion(line);
        return invitesReading(line) && (question.isEmpty() || invitesReading(question));
    }

    private static boolean invitesReading(String line) {
        return line.contains("跟读") || line.contains("跟我读") || line.contains("跟我念")
                || line.contains("读一遍") || line.contains("读一下") || line.contains("读这")
                || line.contains("请读") || line.contains("试着读") || line.contains("你来读")
                || line.contains("念一遍") || line.contains("重复") || line.contains("跟我说")
                || line.contains("你来说") || line.contains("说一遍") || line.contains("试着说")
                || line.contains("你可以说") || line.contains("可以这样说")
                || line.contains("repeat") || line.contains("read aloud") || line.contains("read this")
                || line.contains("read that") || line.contains("read it") || line.contains("try reading")
                || line.contains("try saying") || line.contains("try to say")
                || line.contains("you can say") || line.contains("you could say")
                || line.contains("say it") || line.contains("your turn");
    }
}

package cn.adinall.ai.domain.coaching.teaching.service.reply;

import cn.adinall.ai.domain.coaching.teaching.model.valobj.TeachingTaskVO;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 英语解答的业务工具隔离参考：识别明确解释请求，防止把翻译对象当成设置命令。
 * 仅用于禁止业务副作用，不参与 TeachingPolicy、不决定回答形式或要求跟读。
 * 模型仍直接理解并回答本轮原话；此类保留既有工具隔离边界。
 * 例：“cooked 怎么读”需要解答；“I cooked dinner.” 是原目标时仍是跟读；
 * “I cooked dinner 是什么意思”即使包含目标全部单词，也属于新的知识问题。
 * 规则保守地匹配提问结构，不因出现 English、语法等话题词就把个人分享变成问题。
 */
public final class EnglishLearningQuestionPolicy {
    /** 明确中文询问结构；“这个词/这句话什么意思”在口语学习场景按语言知识请求处理。 */
    private static final Pattern CHINESE = Pattern.compile(
            "(?s).*((用|英文|英语).*(怎么说|如何说|怎么表达|如何表达)|怎么读|怎么发音|如何发音|如何读|怎么拼写|"
            + "(词|单词|短语|句子|这句|这句话|英文|英语|表达).*(什么意[思义]|怎么用|如何用)|"
            + "(什么意[思义]|怎么用|如何用).*(词|单词|英文|英语)|"
            + "(怎么|如何|为什么|为何|什么|哪种|哪里|区别|解释|讲讲|教我|不懂).*(语法|时态|过去式|过去时|现在时|完成时|进行时|介词|冠词|单复数)|"
            + "(语法|时态|过去式|过去时|现在时|完成时|进行时|介词|冠词|单复数).*(怎么|如何|为什么|为何|什么|区别|不懂)|"
            + "(帮我|请|能不能|可以).*(翻译|解释.*(词|英文|英语|句子))|翻译成|翻译一下|"
            + "(英文|英语).*(区别|发音|读音)|.+(是什么意思|什么意思|啥意思)|(?=.*[a-z]).*(有什么区别|有何区别)).*");
    /** 英文只认明确请求结构，避免把一般英文聊天问句和问句跟读素材全部当作知识提问。 */
    private static final Pattern ENGLISH = Pattern.compile(
            "(?s)^(?:(?:please|can you|could you|would you)\\s+)?(?:"
            + "what (?:does|do|is the meaning of) .*(?:mean|meaning).*|"
            + "what(?:'s| is) the (?:difference|meaning|pronunciation).*|"
            + "how (?:do|can|should|would) (?:i|you|we) (?:say|pronounce|translate|use|spell).*|"
            + "how (?:is|are) .*pronounced.*|"
            + "(?:explain|translate) .+|"
            + "why (?:do|does|is|are|did|should) .*(?:tense|grammar|past|present|verb|article|preposition).*|"
            + "(?:is|does) .* (?:grammatically correct|correct english|sound natural).*|"
            + "what (?:is|are) .*(?:tense|perfect|grammar|verb|article|preposition).*|"
            + "what (?:is|are) .+ in english.*|"
            + "what does .+ mean.*)$");

    /** 禁止实例化，仅通过静态方法使用此工具类。 */
    private EnglishLearningQuestionPolicy() { }

    /**
     * 判断本轮是不是明确知识询问。若只是完整复述上一跟读目标，优先保留练习性质，
     * 防止把目标 “What does hello mean?” 中的提问结构误判成真实请求。
     * 额外添加“什么意思/怎么读”等提问内容时，不再与目标相等，按真实问题处理。
     */
    public static boolean isQuestion(String text, TeachingTaskVO previous) {
        if (text == null || text.isBlank()) return false;
        String normalized = text.trim().toLowerCase(Locale.ROOT).replace('’', '\'');
        if (isTargetRepeat(text, previous)) return false;
        // 泛指上一句没听懂时保留理解求助；没有具体语言对象，不伪装成词义问题。
        if (normalized.matches("what do you mean[?？.!\\s]*")) return false;
        if (normalized.matches("(?s)^(不要|别|不用)(翻译|解释|纠音|教我).*")) return false;
        return CHINESE.matcher(normalized).matches() || ENGLISH.matcher(normalized).matches();
    }

    /** 完整复述已播目标应留在跟读流程；供知识准入和旧求助分支使用相同边界。 */
    public static boolean isTargetRepeat(String text, TeachingTaskVO previous) {
        return previous != null && previous.reading() && text != null
                && samePhrase(previous.target(), text.toLowerCase(Locale.ROOT).replace('’', '\''));
    }

    /** 忽略标点和空格比较原目标，不忽略额外词语；问句 ASR 丢问号不会改变跟读性质。 */
    private static boolean samePhrase(String target, String text) {
        if (target == null || target.isBlank()) return false;
        return target.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "")
                .equals(text.replaceAll("[^\\p{L}\\p{N}]", ""));
    }
}

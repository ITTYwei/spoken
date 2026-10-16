package cn.adinall.ai.domain.coaching.teaching.service.reply;

import java.util.regex.Matcher;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 功能：判断本轮识别文本是否像对服务端跟读目标的尝试，避免把聊天话语送去纠音。
 * <p>体系架构 / 运行过程：</p>
 * <pre>pending READ target + current ASR -> token overlap -> attempt / ordinary reply</pre>
 * <p>案例：目标 Let's talk.，用户说“你好。” -> 非跟读；说“Let's talk.” -> 跟读。</p>
 * <p>设计要点：</p>
 * <ul><li>只用于阻止无关输入触发纠音，不替代发音评测。</li>
 * <li>识别不确定时保守地视为普通回复，避免错误指正。</li></ul>
 */
public final class ReadingAttemptPolicy {
    private static final Pattern WORD = Pattern.compile("[a-z]+(?:'[a-z]+)?");

    /** 禁止实例化，仅通过静态方法使用此工具类。 */
    private ReadingAttemptPolicy() {
    }

    /** 至少识别出目标中三分之二的不同英文词，才允许按跟读目标评测。 */
    public static boolean isAttempt(String target, String transcript) {
        Set<String> targetWords = words(target);
        Set<String> heardWords = words(transcript);
        if (targetWords.isEmpty() || heardWords.isEmpty()) return false;
        // 追加中文或超出目标的英文词表示新的话语，保守地不当跟读证据。
        // 这是证据准入，不做意图分类：“I cooked dinner 为什么...”必须留给模型理解。
        if (transcript.matches("(?s).*\\p{IsHan}.*")) return false;
        if (!targetWords.containsAll(heardWords)) return false;
        int matched = 0;
        for (String word : targetWords) if (heardWords.contains(word)) matched++;
        return matched * 3 >= targetWords.size() * 2;
    }

    /** 提取用于比较的英文单词并统一大小写。 */
    private static Set<String> words(String text) {
        Set<String> result = new HashSet<>();
        if (text == null) return result;
        Matcher matcher = WORD.matcher(text.toLowerCase(Locale.ROOT).replace('’', '\''));
        while (matcher.find()) result.add(matcher.group());
        return result;
    }
}

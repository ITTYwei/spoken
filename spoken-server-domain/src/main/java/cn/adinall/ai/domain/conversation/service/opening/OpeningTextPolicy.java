package cn.adinall.ai.domain.conversation.service.opening;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 验证问句、空槽位和文案红线，拦截不完整或施压的首句。
 * <p>
 * 功能：验证问句、空槽位和文案红线，拦截不完整或施压的首句。
 * <p>
 * 体系架构 / 运行过程：
 * <pre>
 *   模板及素材 -> 文案校验 -> 放行 / 跳过
 * </pre>
 * 案例：
 * <pre>
 *   输入 -> 输出：Hi {name}, ready? -> false；Hi, ready? -> true
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>一个问句结尾。</li>
 *   <li>中英文禁词。</li>
 *   <li>新增文案仍需人工审阅。</li>
 * </ul>
 */
public final class OpeningTextPolicy {
    private static final List<String> BANNED = List.of("你太差", "退步", "落后", "水平低", "英语差", "打卡", "别断", "不要断", "补回来", "别偷懒", "坚持", "falling behind", "poor english", "bad english", "don't break", "keep your streak", "catch up", "lazy");
    private static final Pattern SLOT = Pattern.compile("[{}<>]|\\$|\\b(null|undefined)\\b", Pattern.CASE_INSENSITIVE);
    public static boolean safeMaterial(String text) {
        return text != null && !text.isBlank() && text.length() <= 80 && !text.matches("(?s).*[?？\\r\\n\\p{Cntrl}].*")
                && !SLOT.matcher(text).find() && !banned(text);
    }
    public static boolean valid(String text) {
        if (text == null || text.isBlank() || SLOT.matcher(text).find() || banned(text)
                || text.matches("(?s).*[\\r\\n\\p{Cntrl}].*")) return false;
        long questions = text.chars().filter(c -> c == '?' || c == '？').count();
        return questions == 1 && (text.endsWith("?") || text.endsWith("？"));
    }
    /** 首次介绍以一个明确开始动作收尾；再次开场仍保持唯一问句。 */
    public static boolean valid(String text,String context) {
        if(!"FIRST".equals(context)) return valid(text);
        return text!=null && !text.isBlank() && text.length()<=200 && !SLOT.matcher(text).find() && !banned(text)
                && !text.matches("(?s).*[?？\\r\\n\\p{Cntrl}].*") && text.endsWith("先打个招呼吧，中文或英文都可以。");
    }
    private static boolean banned(String text) {
        String normalized = text.toLowerCase(Locale.ROOT).replace('’', '\'');
        return BANNED.stream().anyMatch(normalized::contains);
    }
    private OpeningTextPolicy() {}
}

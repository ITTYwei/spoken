package cn.adinall.ai.domain.coaching.practice.service;

import java.util.Locale;
import java.util.Set;

/**
 * 自然语言动作的有限业务护栏。Agent 负责理解复杂表达，规则只拒绝练习/引用台词，
 * 并识别有播报任务绑定的明确短指令；不使用任意关键词命中后直接更改状态。
 * 例：“请解释‘继续’”不执行，“好”只有询问已播完才有含义，“不继续”明确选择自由聊。
 */
public final class PracticeIntentPolicy {
    /** 禁止实例化，仅通过静态方法使用此工具类。 */
    private PracticeIntentPolicy() { }
    /** 归一仅去两端空白和结束标点，不删掉否定词或引用符号。 */
    public static String normalized(String text) { return text==null?"":text.trim().toLowerCase(Locale.ROOT).replaceAll("[。.!！?？]+$","").trim(); }
    /** 引用、解释、假设或角色扮演包含指令词时，不能当成控制命令。 */
    public static boolean quotedOrRole(String text) {
        String value=normalized(text);
        return value.matches("(?s).*[\"“”‘’「」].*") || value.matches("(?s).*(?:^|\\s)'[^']+'.*") ||
                value.matches("(?s).*(跟读|照着读|角色扮演|假设|如果|假如|例句|引用|这句话|这个词|他说|她说|repeat after|role.?play|quote|suppose|for example|what does .* mean).*" );
    }
    /** “我忘了带钥匙”是叙述，不能触发记忆删除；只接受明确面向伙伴的遗忘请求。 */
    public static boolean forgetRequest(String text) {
        if(quotedOrRole(text)) return false;
        String value=normalized(text);
        return value.matches("(?s)^(?:请|麻烦|帮我|please\\s+)?(?:忘掉|忘记(?:所有|全部|我的|之前的)|删除(?:我.*)?记忆|forget\\s+|delete .*memor).*" ) ||
                value.matches("(?s)^.*(?:请你|希望你|要求你|i want you to).*(?:忘掉|forget).*" );
    }
    /** 最小兜底：明确继续可直接选真实最近课；短肯定必须绑定已播出的待选择任务。 */
    public static String fallbackMode(String text, boolean pending) {
        if (quotedOrRole(text)) return null;
        String value=normalized(text);
        if(Set.of("继续","继续上次","继续课程","继续上次课程","continue","resume","resume the lesson").contains(value)) return "COURSE";
        if(pending && Set.of("好","好的","可以","行","yes","okay","ok","sure").contains(value)) return "COURSE";
        if(Set.of("不继续","先不继续","自由聊","自由对话","切换自由对话","自由聊天","free chat","switch to free chat").contains(value)) return "FREE";
        return null;
    }
    /** 允许 Agent 理解明确练习操作，但不能把一般个人陈述转成模式切换。 */
    public static boolean allowsSwitch(String text, String mode, boolean pending) {
        if(quotedOrRole(text)) return false;
        String direct=fallbackMode(text,pending);
        if(direct!=null) return direct.equals(mode);
        String value=normalized(text);
        if("FREE".equals(mode)) {
            if(value.matches("(?s).*(不想自由|不要自由|别自由|don't.*free chat|do not.*free chat).*")) return false;
            return value.matches("(?s).*(自由|随便聊|只想聊|just chat|free chat|chat normally|casual chat).*" );
        }
        if("COURSE".equals(mode) && value.matches("(?s).*(不想|不要|不继续|别|don't|do not|not .*continue|not .*practice).*")) return false;
        return "COURSE".equals(mode) && value.matches("(?s).*(练|课程|教材|继续|上次|接着|learn|practice|course|lesson|resume|continue).*" );
    }
}

package cn.adinall.ai.domain.identity.service.security;

import cn.adinall.ai.domain.coaching.practice.service.PracticeIntentPolicy;

/**
 * 明确长期偏好写入规则，目标与兴趣采用不同策略条件。
 * Agent 可归纳用户明说的事实，但原文必须支持对应类型；临时练习需求不能变成长久画像。
 * 例：“今天练面试”不存；“以后重点练面试”可存；跟读“I like coffee”不存。
 */
public final class PreferenceIntentPolicy {
    /** 禁止实例化，仅通过静态方法使用此工具类。 */
    private PreferenceIntentPolicy() { }
    /** 判断用户原话是否要求显式确认偏好保存或目标删除。 */
    public static boolean requiresConfirmation(String text) {
        String value=PracticeIntentPolicy.normalized(text);
        boolean explicitSave=value.matches("(?s).*(保存|记住|记一下|记录下来|更正|修改|改为|改成|remember|save|update|correct).*");
        return (explicitSave && (permits(text,"SAVE","GOAL") || permits(text,"SAVE","PREFERENCE"))) || permits(text,"DELETE","GOAL");
    }
    /** 更正与删除必须有明确管理意图；普通否定喜好可作为新兴趣事实。 */
    public static boolean permits(String text,String action,String type) {
        if(PracticeIntentPolicy.quotedOrRole(text)) return false;
        String value=PracticeIntentPolicy.normalized(text);
        // 查询和拒绝管理也包含“学习目标/删除”等词，不能仅靠这些词允许写入。
        if(value.matches("(?s).*(不要删除|别删除|不用删除|不要保存|别保存|不要记住|别记住|don't delete|do not delete|don't save|do not save).*")) return false;
        if(value.matches("(?s).*(查看|查询|我的.*(?:是什么|有哪些|有什么)|你记得我的.*吗|告诉我.*(?:目标|偏好)|what .*learning goal|what .*preferences|show .*preferences|list .*preferences).*")) return false;
        if("DELETE".equals(action)) return PracticeIntentPolicy.forgetRequest(text) ||
                value.matches("(?s).*(删除|取消.*目标|移除|别再记|delete|remove).*" );
        if(!"SAVE".equals(action)) return false;
        if(value.matches("(?s).*(今天|这次|现在|本次|暂时|today|this time|for now|temporarily).*")) return false;
        if("GOAL".equals(type)) return value.matches("(?s).*(以后|今后|长期|学习目标|从现在起|记住.*练|from now on|learning goal|long.term|always.*practice|remember.*practice).*" );
        if("PREFERENCE".equals(type)) return value.matches("(?s).*(我喜欢|我不喜欢|我不再喜欢|我的兴趣|我更喜欢|i like |i prefer |i don't like |i no longer like |my interests).*" );
        return false;
    }
}

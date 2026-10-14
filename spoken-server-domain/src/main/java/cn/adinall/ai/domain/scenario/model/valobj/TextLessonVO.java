package cn.adinall.ai.domain.scenario.model.valobj;

import java.util.List;

/**
 * 一课完整文本，属于 scenario 领域；原题和本项目辅助教学内容明确分离。
 * 本轮提示词必须全量保留本课题目，避免裁剪历史时意外丢失教材。
 * @param id 本项目稳定课编号
 * @param title 用户可理解的课名
 * @param objective 本项目编写的练习目标
 * @param sourceCardId 原始题卡编号
 * @param questions 原始完整题组，不修改原文
 * @param addedGuidance 本项目编写的陪练指导，不冒充教材原文
 * @param aliases 本项目标注的明确课名别称，用于拒绝不相关课程选择；含糊话题须先澄清
 */
public record TextLessonVO(String id, String title, String objective, String sourceCardId,
                           List<String> questions, String addedGuidance, List<String> aliases) {
    public TextLessonVO { questions = List.copyOf(questions); aliases=aliases==null?List.of(title,id):List.copyOf(aliases); }
    /** 兼容既有手工教材定义，未提供别称时只接受明确课名或编号。 */
    public TextLessonVO(String id,String title,String objective,String sourceCardId,List<String> questions,String addedGuidance) {
        this(id,title,objective,sourceCardId,questions,addedGuidance,null);
    }
    /** 只支持教材声明的明确引用，不靠“练”这个关键词猜第一课；语义含糊交给 Agent 澄清。 */
    public boolean explicitlyReferenced(String text) {
        String value=text.toLowerCase(java.util.Locale.ROOT);
        return aliases.stream().anyMatch(alias->alias.length()>=2 && value.contains(alias.toLowerCase(java.util.Locale.ROOT)));
    }

    /** 给模型的教材数据段；不是用户指令，只约束练习范围，一次只问一题。 */
    public String context() {
        return "当前文本课程数据：" + title + "\n教材原题：\n" +
                String.join("\n", questions) + "\n本项目陪练指导：" + addedGuidance + "\n";
    }
}

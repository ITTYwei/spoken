package cn.adinall.ai.domain.coaching.conversation.model.valobj;

/**
 * 对话的业务场景背景，与已播任务分别管理；进入课程后仍可能临时解答知识问题或安排跟读。
 * 场景只说明练习环境，不能覆盖本轮用户新需求、语言档位或工具鉴权。
 */
public enum ConversationSceneEnum {
    /** 自由英语交流与知识学习；不意味着允许展开无关业务咨询。 */
    FREE_ENGLISH("自由英语交流与学习"),
    /** 用户已明确选择文本课程，完整教材仍作为本轮必需背景。 */
    TEXTBOOK_PRACTICE("文本课程陪练"),
    /** 非自由场景的角色练习，具体编号与人设由服务端提供，模型不能据此编造用户事实。 */
    ROLE_PRACTICE("指定情境下的英语口语练习");

    /** 给模型的自然语言场景说明，不是应播报的台词。 */
    private final String description;
    /** 创建 {@code ConversationSceneEnum}，初始化其依赖与状态。 */
    ConversationSceneEnum(String description) { this.description = description; }
    /** 返回只读背景说明，保留英文枚举用于服务端类型判断。 */
    public String description() { return description; }
}

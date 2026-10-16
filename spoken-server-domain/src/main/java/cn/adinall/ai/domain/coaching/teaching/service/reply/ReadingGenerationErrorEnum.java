package cn.adinall.ai.domain.coaching.teaching.service.reply;

/** 跟读草稿的业务校验原因；固定中文修正建议可反馈给模型，不包含草稿、用户隐私或堆栈。 */
public enum ReadingGenerationErrorEnum {
    /** 无可识别的英文目标，不能确定评测题面。 */
    MISSING_TARGET("没有可识别的英文目标，请用双引号标出一个不超过180字符的英文目标"),
    /** 存在多个不同目标，不能擅自挑第一句；同一句重复引用不属于此错误。 */
    MULTIPLE_TARGETS("存在多个不同的英文目标，请只保留一个目标；其他讲解不要引用新的英文句子"),
    /** 新题与近期已播内容重复，不能换标点冒充新题。 */
    RECENT_DUPLICATE("新目标与近期已练英文重复，请结合当前情境生成另一句，不只换标点"),
    /** 纠音时擅自改题，会使上游证据失去对应关系。 */
    TARGET_CHANGED("重读时更换了原目标，请保持 suggested_target 原句不变"),
    /** 明确说不必读，与本轮主动选择的跟读示范矛盾。 */
    READING_DECLINED("正文表示不用跟读，与本轮跟读示范任务冲突，请保留示范并邀请朗读"),
    /** 缺少最低限度讲解，不能只播放孤立英文题面。 */
    MISSING_EXPLANATION("缺少目标以外的情境或含义讲解，请补充简短自然的说明"),
    /** 低档位需要中文支援，不能只输出英文。 */
    MISSING_CHINESE("当前为L1或L2，请在英文后补充简短中文含义");

    /** 固定业务原因和修正动作；只描述失败约束，不包含模型原始输出。 */
    private final String correction;
    /** 创建 {@code ReadingGenerationErrorEnum}，初始化其依赖与状态。 */
    ReadingGenerationErrorEnum(String correction) { this.correction = correction; }
    /** 供日志诊断及本轮有限重生成使用，不作为用户播报台词。 */
    public String correction() { return correction; }
}

package cn.adinall.ai.domain.conversation.model.valobj;

/**
 * 教学动作的稳定码定义，与 TeachingOutcomeEnum 的输入结果分别管理。
 * 原任务对象的 action 暂保留字符串协议形式，新能力通过本枚举创建和判断，避免新增魔法值。
 * 例：用户询问词义时执行 EXPLAIN，既不是跟读，也不是要求用户回答一个练习问题。
 */
public enum TeachingActionEnum {
    /** 一个与用户内容相关的练习问题，等待用户自主回答。 */
    QUESTION,
    /** 示范一个英文目标并邀请跟读；目标只有实际播完后才生效。 */
    READ,
    /** 有可信目标词发音证据时温和指导，并有限次邀请重读。 */
    CORRECT,
    /** 兼容既有任务协议的澄清动作，供明确意图不足时使用。 */
    CLARIFY,
    /** 直接解答用户的英语知识问题，允许解释和例句，不强制跟读或练习追问。 */
    EXPLAIN;

    /** 保持现有 action 文本协议，禁止使用 ordinal 入库。 */
    public String code() { return name(); }
}

package cn.adinall.ai.domain.conversation.model.valobj;

/**
 * 功能：保存一次且仅一次用户任务，供下一轮判断完成、求助或跳过。
 * <p>体系架构 / 运行过程：</p>
 * <pre>本轮决策 -> 播报完成 -> pending task -> 下一轮反馈</pre>
 * <p>案例：READ("I cooked dinner.",1) -> 不再同时询问做了什么菜。</p>
 * <p>设计要点：</p>
 * <ul><li>问题和跟读目标分开保存。</li><li>播报被取消时不提交新任务。</li></ul>
 */
public record TeachingTaskVO(
        /** QUESTION / READ / CORRECT / CLARIFY。 */ String action,
        /** 服务端确定的跟读目标；提问时为空。 */ String target,
        /** 已实际进入完整播报流程的问题；非提问任务为空。 */ String question,
        /** 当前同一目标的尝试次数，不重复累计能力证据。 */ int attempts) {
    /** 是否需要对照固定目标评测。 */
    public boolean reading() { return "READ".equals(action) || "CORRECT".equals(action); }
    /** 开场没有可用原文时也明确属于问答任务。 */
    public static TeachingTaskVO question(String text) { return new TeachingTaskVO("QUESTION", "", text, 0); }
}

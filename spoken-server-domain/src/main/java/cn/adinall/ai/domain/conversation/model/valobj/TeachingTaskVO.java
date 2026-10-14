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
        /** 稳定动作码，定义见 TeachingActionEnum；保留既有字符串协议。 */
        String action,
        /** 已确认播出的跟读目标；新跟读尚待模型生成或提问时为空。 */
        String target,
        /** 已实际进入完整播报流程的问题；非提问任务为空。 */
        String question,
        /** 当前同一目标的尝试次数，不重复累计能力证据。 */
        int attempts) {
    /**
     * 是否需要对照固定目标评测。
     */
    public boolean reading() {
        return TeachingActionEnum.READ.code().equals(action) || TeachingActionEnum.CORRECT.code().equals(action);
    }

    /**
     * 开场没有可用原文时也明确属于问答任务。
     */
    public static TeachingTaskVO question(String text) {
        return new TeachingTaskVO(TeachingActionEnum.QUESTION.code(), "", text, 0);
    }

    /** 英语知识解答只安排解释，不携带待跟读目标或等待用户作答的问题。 */
    public static TeachingTaskVO explanation() { return new TeachingTaskVO(TeachingActionEnum.EXPLAIN.code(), "", "", 0); }

    /** 解答流程与练习问答的区别：允许完整解释，不强制提问、评分或跟读。 */
    public boolean explaining() { return TeachingActionEnum.EXPLAIN.code().equals(action); }
}

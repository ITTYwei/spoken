package cn.adinall.ai.domain.agent.model.valobj;

/**
 * 功能：表达一个有优先级和预算的可选上下文片段。
 * <p>体系架构 / 运行过程：</p>
 * <pre>provider -> section -> priority reducer -> prompt</pre>
 * <p>案例：旧话题摘要优先于无关事实被丢弃。</p>
 * <p>设计要点：</p>
 * <ul><li>较小 priority 表示更重要。</li><li>当前任务不作为可选片段。</li></ul>
 */
public record ContextSectionVO(
        /** 片段来源。 */ String name,
        /** 保留优先级。 */ int priority,
        /** 只含引用数据的片段文本。 */ String text) {}

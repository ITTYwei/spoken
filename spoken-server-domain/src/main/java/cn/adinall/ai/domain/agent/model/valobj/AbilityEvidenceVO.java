package cn.adinall.ai.domain.agent.model.valobj;

/**
 * 功能：表示模型对上一问题和本轮回答的独立观察，不直接修改等级。
 * <p>体系架构 / 运行过程：</p>
 * <pre>background model -> strict JSON -> observation -> learner policy</pre>
 * <p>案例：明确拒绝 -> DECLINED，不被描述成英语差。</p>
 * <p>设计要点：</p>
 * <ul><li>不含发音分。</li><li>不确定语义关联时必须返回 UNCERTAIN。</li></ul>
 */
public record AbilityEvidenceVO(
        /** ANSWERED / PARTIAL / HELP / DECLINED / UNCERTAIN。 */ String outcome,
        /** 产品候选级别 L1..L6，非认证。 */ String candidate,
        /** 观察可信度。 */ double confidence,
        /** 是否有独立、相关而完整的表达证据。 */ boolean independent) {}

package cn.adinall.ai.domain.conversation.model.valobj;

import cn.adinall.ai.domain.learner.model.valobj.EnglishLevelEnum;

import java.time.Instant;

/**
 * 功能：冻结本轮参考建议、有效输入证据和语言支持，防止旧轮次指令重新生效。
 * <p>体系架构 / 运行过程：</p>
 * <pre>上一任务 + 用户反馈 + 可靠评测 -> Decision -> case / Agent</pre>
 * <p>案例：用户“不想读” -> outcome=DECLINED、action=QUESTION、拒绝不扣等级。</p>
 * <p>设计要点：</p>
 * <ul><li>仅传任务和可信纠音线索，由 Agent 组织播报话术。</li>
 * <li>拒绝、收音失败和练习素材不能被当作长期事实或升级证据。</li></ul>
 */
public record TeachingDecisionVO(
        /** 当前会话冻结的六级水平。 */
        EnglishLevelEnum level,
        /** 本轮教学参考建议；实际待完成任务以模型真实播报后的解析为准。 */
        TeachingTaskVO nextTask,
        /** 对上一任务的响应结果，非空；具体触发条件及处理边界见 TeachingOutcomeEnum。 */
        TeachingOutcomeEnum outcome,
        /** 此轮是否使用额外中文帮助，与能力等级分别管理。 */
        boolean chineseSupport,
        /** 可信错词的简短纠音线索；不包含可直接播报的整句。 */
        String correctionHint,
        /** 评测是否有服务端固定目标和具体可信错误证据。 */
        boolean reliableAssessment,
        /** 输入来自跟读任务，禁止提取用户偏好。 */
        boolean practiceInput,
        /** 判定时冻结的上一任务，不在异步生成阶段重新读取可变状态。 */
        TeachingTaskVO previousTask,
        /** 当前用户输入产生时刻，异步观察完成不能改变证据窗口。 */
        Instant inputAt) {
    /** 结果必须明确；不允许用 null 静默进入提示词默认分支。 */
    public TeachingDecisionVO {
        java.util.Objects.requireNonNull(outcome, "教学结果不能为空");
    }

    public TeachingDecisionVO(EnglishLevelEnum level, TeachingTaskVO nextTask, TeachingOutcomeEnum outcome, boolean chineseSupport,
                              String correctionHint, boolean reliableAssessment, boolean practiceInput) {
        this(level, nextTask, outcome, chineseSupport, correctionHint, reliableAssessment, practiceInput, null, null);
    }
}

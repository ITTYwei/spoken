package cn.adinall.ai.domain.conversation.model.valobj;

import cn.adinall.ai.domain.learner.model.valobj.EnglishLevelEnum;

/**
 * 功能：冻结本轮任务、有效输入证据和语言支持，防止旧轮次指令重新生效。
 * <p>体系架构 / 运行过程：</p>
 * <pre>上一任务 + 用户反馈 + 可靠评测 -> Decision -> case / Agent</pre>
 * <p>案例：用户“不想读” -> outcome=DECLINED、action=QUESTION、拒绝不扣等级。</p>
 * <p>设计要点：</p>
 * <ul><li>script 非空时采用确定性教学句，不调用模型猜目标。</li>
 * <li>拒绝、收音失败和练习素材不能被当作长期事实或升级证据。</li></ul>
 */
public record TeachingDecisionVO(
        /** 当前会话冻结的六级水平。 */ EnglishLevelEnum level,
        /** 本轮唯一任务。 */ TeachingTaskVO nextTask,
        /** 上一任务响应结果：RESPONDED / HELP / DECLINED / NO_AUDIO / READ_PASS / READ_RETRY。 */ String outcome,
        /** 此轮是否使用额外中文帮助，与能力等级分别管理。 */ boolean chineseSupport,
        /** 可直接播报的教学句；空值时由 Agent 生成问题回复。 */ String script,
        /** 评测是否有服务端固定目标和具体可信错误证据。 */ boolean reliableAssessment,
        /** 输入来自跟读任务，禁止提取用户偏好。 */ boolean practiceInput,
        /** 判定时冻结的上一任务，不在异步生成阶段重新读取可变状态。 */ TeachingTaskVO previousTask,
        /** 当前用户输入产生时刻，异步观察完成不能改变证据窗口。 */ java.time.Instant inputAt) {
    public TeachingDecisionVO(EnglishLevelEnum level,TeachingTaskVO nextTask,String outcome,boolean chineseSupport,
                              String script,boolean reliableAssessment,boolean practiceInput) {
        this(level,nextTask,outcome,chineseSupport,script,reliableAssessment,practiceInput,null,null);
    }
}

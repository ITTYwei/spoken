package cn.adinall.ai.domain.conversation.model.valobj;

/**
 * 功能：关联待完成媒体轮次和教学任务，使整段回退也按媒体结束提交。
 * <p>体系架构 / 运行过程：</p>
 * <pre>begin -> pending metadata -> ASSISTANT_DONE -> commit / interrupt -> discard</pre>
 * <p>案例：/say返回但语音未结束 -> 不提交新跟读目标。</p>
 * <p>设计要点：</p>
 * <ul><li>只有当前replyId可提交。</li><li>结束语义是服务器媒体排空。</li></ul>
 */
public record TeachingPlaybackVO(
        /** 关联用户回合；按钮主动示范为空，不写用户回合或教学回合表。 */ Long turnId,
        /** 回复唯一ID。 */ String replyId,
        /** 本轮唯一动作。 */ TeachingDecisionVO decision) {}

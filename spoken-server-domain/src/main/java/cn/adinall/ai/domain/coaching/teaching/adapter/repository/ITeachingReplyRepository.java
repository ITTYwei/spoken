package cn.adinall.ai.domain.coaching.teaching.adapter.repository;

import cn.adinall.ai.domain.coaching.teaching.model.valobj.TeachingDecisionVO;

/**
 * 功能：标记有效教学回复，控制历史只收录完整成功播报。
 * <p>体系架构 / 运行过程：</p>
 * <pre>prepare -> begin(completed=false) -> media drained -> complete</pre>
 * <p>案例：模型生成完但用户打断 -> completed=false，不召回为完整历史。</p>
 * <p>设计要点：</p>
 * <ul><li>状态归 conversation。</li><li>按 replyId 条件提交，不能覆盖新回复。</li></ul>
 */
public interface ITeachingReplyRepository {
    /** 登记待播放的教学决策，以回合和回复标识关联后续完成通知。 */
    void begin(String subject,String session,Long turn,String replyId,TeachingDecisionVO decision);
    /** 按回合与回复标识回填已实际播出的教学文本。 */
    void complete(Long turn,String replyId,String text);
}

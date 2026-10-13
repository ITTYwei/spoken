package cn.adinall.ai.domain.conversation.model.aggregate;

import cn.adinall.ai.domain.conversation.model.valobj.TeachingTaskVO;
import cn.adinall.ai.domain.learner.model.valobj.EnglishLevelEnum;

/**
 * 功能：保存会话内冻结等级及上一项有效教学任务。
 * <p>体系架构 / 运行过程：</p>
 * <pre>initialize -> propose reply -> commit after media drain -> next input
 * interrupt -> clear pending task (no assumed unheard question)</pre>
 * <p>案例：生成了问题但中途被打断 -> pending 清空，不认定用户拒绝回答。</p>
 * <p>设计要点：</p>
 * <ul><li>等级在本会话内不升级。</li><li>方法同步；旧轮次提交还需要外层 replyId 校验。</li></ul>
 */
public final class TeachingStateAggregate {
    /** 会话期间稳定的能力等级。 */
    private EnglishLevelEnum level;
    /** 上一轮完整播报后等待用户完成的任务。 */
    private TeachingTaskVO pending = TeachingTaskVO.question("");
    public synchronized EnglishLevelEnum initialize(EnglishLevelEnum initial) {
        if (level == null) level = initial;
        return level;
    }
    public synchronized TeachingTaskVO pending() { return pending; }
    public synchronized EnglishLevelEnum level() { return level; }
    public synchronized void commit(TeachingTaskVO task) { pending = task; }
    /** 不能认为被打断回复中的新任务已经被用户听到。 */
    public synchronized void interrupt() { pending = TeachingTaskVO.question(""); }
}

package cn.adinall.ai.domain.learner.adapter.repository;

import cn.adinall.ai.domain.learner.model.valobj.*;
import java.time.Instant;
import java.util.List;

/**
 * 功能：持久化稳定等级和可追溯回答证据。
 * <p>体系架构 / 运行过程：</p>
 * <pre>learner service -> repository -> profile / evidence</pre>
 * <p>案例：同 turnId 重试 -> 仍只有一条证据。</p>
 * <p>设计要点：</p>
 * <ul><li>按 subjectKey 隔离。</li><li>升级使用乐观条件更新，不能并发连跳。</li></ul>
 */
public interface ILearnerProgressRepository {
    EnglishLevelEnum level(String subjectKey);
    /** 手动陪练档位；null 表示使用自动适配，不能据此修改能力证据。 */
    EnglishLevelEnum manualLevel(String subjectKey);
    /** 保存手动档位，null 恢复自动；主体来自认证上下文，更新独立列。 */
    void setManualLevel(String subjectKey, EnglishLevelEnum level);
    void record(LearningEvidenceVO evidence);
    List<LearningEvidenceVO> recent(String subjectKey, Instant since);
    /** 页面描述使用全部近期有效证据，不因升档消费证据而重新显示“正在了解”。 */
    default List<LearningEvidenceVO> recentForDescription(String subjectKey, Instant since) { return recent(subjectKey, since); }
    boolean promote(String subjectKey, EnglishLevelEnum from, EnglishLevelEnum to, String sessionId);
}

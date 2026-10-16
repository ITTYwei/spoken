package cn.adinall.ai.domain.learning.adapter.repository;

import cn.adinall.ai.domain.learning.model.valobj.*;
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
    /** 读取主体或会话当前生效的学习档位。 */
    EnglishLevelEnum level(String subjectKey);
    /** 手动陪练档位；null 表示使用自动适配，不能据此修改能力证据。 */
    EnglishLevelEnum manualLevel(String subjectKey);
    /** 保存手动档位，null 恢复自动；主体来自认证上下文，更新独立列。 */
    void setManualLevel(String subjectKey, EnglishLevelEnum level);
    /** 持久化本轮学习证据，保留来源会话与回合。 */
    void record(LearningEvidenceVO evidence);
    /** 读取指定主体范围内的近期记录。 */
    List<LearningEvidenceVO> recent(String subjectKey, Instant since);
    /** 页面描述使用全部近期有效证据，不因升档消费证据而重新显示“正在了解”。 */
    default List<LearningEvidenceVO> recentForDescription(String subjectKey, Instant since) { return recent(subjectKey, since); }
    /** 仅在原档位仍匹配时提交升级，防止并发评估覆盖新结果。 */
    boolean promote(String subjectKey, EnglishLevelEnum from, EnglishLevelEnum to, String sessionId);
}

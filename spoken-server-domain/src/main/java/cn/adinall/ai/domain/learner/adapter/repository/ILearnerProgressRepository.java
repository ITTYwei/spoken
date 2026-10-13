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
    void record(LearningEvidenceVO evidence);
    List<LearningEvidenceVO> recent(String subjectKey, Instant since);
    boolean promote(String subjectKey, EnglishLevelEnum from, EnglishLevelEnum to, String sessionId);
}

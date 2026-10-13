package cn.adinall.ai.domain.agent.adapter.repository;

import cn.adinall.ai.domain.agent.model.entity.LongTermMemoryEntity;
import java.time.Instant;
import java.util.List;

/**
 * 功能：持久化可更正和删除的长期记忆。
 * <p>体系架构 / 运行过程：</p>
 * <pre>memory service -> scoped repository -> upsert / recall / delete</pre>
 * <p>案例：过期的临时语言偏好不再召回。</p>
 * <p>设计要点：</p>
 * <ul><li>所有操作必须携带 subjectKey。</li><li>同事实键替换而非追加冲突记忆。</li></ul>
 */
public interface ILongTermMemoryRepository {
    void save(LongTermMemoryEntity memory);
    List<LongTermMemoryEntity> active(String subjectKey, Instant now);
    void forget(String subjectKey, String memoryKey);
    void forgetAll(String subjectKey);
    /** 显式遗忘后不再用较早原文重新召回已删除事实。 */
    void forgetHistoryBefore(String subjectKey, Long turnId);
    /** 原子删除记忆和更新历史边界，不能只完成一半。 */
    void forgetAllAndHistory(String subjectKey, Long turnId);
    /** 主题按字面匹配，不将百分号等用户字符当SQL通配符。 */
    void forgetMatchingAndHistory(String subjectKey,String topic,Long turnId);
}

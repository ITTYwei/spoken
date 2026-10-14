package cn.adinall.ai.domain.practice.adapter.repository;

import cn.adinall.ai.domain.practice.model.valobj.RecentLessonVO;
import java.util.Optional;

/** 最近课程按可信教学主体隔离；持久化只发生在实际选课成功时。 */
public interface IRecentLessonRepository {
    /** 不存在返回空，不把最近课程当成当前模式。 */
    Optional<RecentLessonVO> find(String subject);
    /** 原子覆盖主体的最近课；失败必须阻止当前切换提交。 */
    void save(String subject, RecentLessonVO lesson);
}

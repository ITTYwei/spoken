package cn.adinall.ai.infrastructure.adapter.repository;

import cn.adinall.ai.domain.coaching.practice.adapter.repository.IRecentLessonRepository;
import cn.adinall.ai.domain.coaching.practice.model.valobj.RecentLessonVO;
import cn.adinall.ai.infrastructure.dao.IRecentLessonDao;
import org.springframework.stereotype.Repository;
import java.util.Optional;

/** 最近课仓储适配器，只负责 PO 与课程引用转换；练习版本规则在 practice 聚合。 */
@Repository
public class RecentLessonRepository implements IRecentLessonRepository {
    /** 主体隔离的数据访问入口，不接受工具自行指定账号。 */
    private final IRecentLessonDao dao;
    /** 创建 {@code RecentLessonRepository}，初始化其依赖与状态。 */
    public RecentLessonRepository(IRecentLessonDao dao) { this.dao = dao; }
    /** 没有练过返回空，不能用目录第一课冒充最近课。 */
    @Override public Optional<RecentLessonVO> find(String subject) {
        return Optional.ofNullable(dao.find(subject)).map(r -> new RecentLessonVO(r.getTextbookId(), r.getLessonId()));
    }
    /** 原子保存引用，数据库错误向业务调用方传播。 */
    @Override public void save(String subject, RecentLessonVO lesson) { dao.save(subject, lesson.textbookId(), lesson.lessonId()); }
}

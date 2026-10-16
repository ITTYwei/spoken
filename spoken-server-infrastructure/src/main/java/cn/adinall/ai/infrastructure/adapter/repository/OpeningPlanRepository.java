package cn.adinall.ai.infrastructure.adapter.repository;

import cn.adinall.ai.infrastructure.dao.po.OpeningPlanPO;

import cn.adinall.ai.domain.coaching.practice.adapter.repository.IOpeningPlanRepository;
import cn.adinall.ai.domain.coaching.practice.model.valobj.PlannedTopicVO;
import cn.adinall.ai.infrastructure.dao.IOpeningFactsDao;
import org.springframework.stereotype.Repository;
import java.time.LocalDate;
import java.util.Optional;

/**
 * 把可信账号今日计划转换为可选的首句素材。
 * <p>
 * 功能：把可信账号今日计划转换为可选的首句素材。
 * <p>
 * 体系架构 / 运行过程：
 * <pre>
 *   账号 / 日期 / 场景 -> DAO -> 主题及来源
 * </pre>
 * 案例：
 * <pre>
 *   输入 -> 输出：匿名主体 -> 空素材；条目7 -> plan_item:7
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>无认证不查库。</li>
 *   <li>未安排今日、已完成和不同场景的事项跳过。</li>
 * </ul>
 */
@Repository
public class OpeningPlanRepository implements IOpeningPlanRepository {
    private final IOpeningFactsDao dao;
    /** 创建 {@code OpeningPlanRepository}，初始化其依赖与状态。 */
    public OpeningPlanRepository(IOpeningFactsDao dao) { this.dao = dao; }
    /** 查找账号当天在指定场景中的开场计划。 */
    public Optional<PlannedTopicVO> findToday(Long userId, LocalDate today, String scenarioId) {
        if (userId == null) return Optional.empty();
        OpeningPlanPO plan = dao.plan(userId, today, scenarioId);
        return plan == null ? Optional.empty() : Optional.of(new PlannedTopicVO(plan.getOpeningTopicZh(), plan.getOpeningTopicEn(), "plan_item:" + plan.getId()));
    }
}

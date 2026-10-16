package cn.adinall.ai.domain.coaching.practice.adapter.repository;

import cn.adinall.ai.domain.coaching.practice.model.valobj.PlannedTopicVO;
import java.time.LocalDate;
import java.util.Optional;

/**
 * 读取今天明确安排且仍未完成的练习素材。
 * <p>
 * 功能：读取今天明确安排且仍未完成的练习素材。
 * <p>
 * 体系架构 / 运行过程：
 * <pre>
 *   可信用户 + 日期 + 场景 -> 计划仓储 -> 可选素材
 * </pre>
 * 案例：
 * <pre>
 *   输入 -> 输出：userId=null -> Optional.empty
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>截止日不充当日程。</li>
 *   <li>匿名请求不读取账号私有计划。</li>
 * </ul>
 */
public interface IOpeningPlanRepository {
    /** 查找账号当天在指定场景中的开场计划。 */
    Optional<PlannedTopicVO> findToday(Long authenticatedUserId, LocalDate today, String scenarioId);
}

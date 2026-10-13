package cn.adinall.ai.infrastructure.dao;

import cn.adinall.ai.infrastructure.dao.po.*;
import org.apache.ibatis.annotations.*;
import java.time.LocalDate;
import java.util.List;

@Mapper

/**
 * 提供首句相关场景、历史、回合、计划与明确待聊事实的 SQL 操作。
 * <p>
 * 功能：提供首句相关场景、历史、回合、计划与明确待聊事实的 SQL 操作。
 * <p>
 * 体系架构 / 运行过程：
 * <pre>
 *   仓储适配器 -> DAO -> OpeningFactsMapper -> MySQL
 * </pre>
 * 案例：
 * <pre>
 *   输入 -> 输出：scenarios() -> 启用且公开的官方场景；plan(null) 由仓储拦截
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>SQL 只筛选事实。</li>
 *   <li>优先级和话术不在 DAO 实现。</li>
 * </ul>
 */
public interface IOpeningFactsDao {
    List<OpeningScenarioPO> scenarios();
    List<OpeningHistorySessionPO> history(@Param("deviceId") String deviceId, @Param("userId") Long userId);
    List<OpeningHistoryTurnPO> turns(@Param("ids") List<String> ids);
    OpeningPlanPO plan(@Param("userId") Long userId, @Param("today") LocalDate today, @Param("scenarioId") String scenarioId);
    int saveResume(@Param("sessionId") String sessionId, @Param("zh") String zh, @Param("en") String en, @Param("evidence") String evidence);
    java.util.Map<String,Object> encounter(@Param("subject") String subject);
    int shown(@Param("subject") String subject,@Param("session") String session,@Param("avatar") String avatar,
              @Param("name") String name,@Param("intro") boolean intro,@Param("at") java.util.Date at);
    int verifiedResume(@Param("session") String session,@Param("turn") Long turn,@Param("zh") String zh,@Param("en") String en);
    int finishTopic(@Param("session") String session,@Param("turn") Long turn);
}

package cn.adinall.ai.infrastructure.dao;

import cn.adinall.ai.infrastructure.dao.po.*;
import org.apache.ibatis.annotations.*;
import java.time.LocalDate;
import java.util.List;

/** 开场场景、历史事实与已展示记录的数据库访问契约。 */
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
    /** 查询开场场景配置。 */
    List<OpeningScenarioPO> scenarios();
    /** 读取指定主体或会话的历史记录。 */
    List<OpeningHistorySessionPO> history(@Param("deviceId") String deviceId, @Param("userId") Long userId);
    /** 按会话与回合游标读取对话记录。 */
    List<OpeningHistoryTurnPO> turns(@Param("ids") List<String> ids);
    /** 最近一场真实会话的已完成非跟读记录；主体隔离与遗忘边界仍由 SQL 校验。 */
    List<OpeningHistoryTurnPO> topicTurns(@Param("sessionId") String sessionId,
                                        @Param("deviceId") String deviceId,@Param("userId") Long userId);
    /** 读取账号当天指定场景的开场计划。 */
    OpeningPlanPO plan(@Param("userId") Long userId, @Param("today") LocalDate today, @Param("scenarioId") String scenarioId);
    /** 保存可用于下次开场的话题摘要与来源证据。 */
    int saveResume(@Param("sessionId") String sessionId, @Param("zh") String zh, @Param("en") String en, @Param("evidence") String evidence);
    /** 读取主体最近实际展示的开场信息。 */
    java.util.Map<String,Object> encounter(@Param("subject") String subject);
    /** 记录已实际展示的开场与形象信息，供下次开场决策使用。 */
    int shown(@Param("subject") String subject,@Param("session") String session,@Param("avatar") String avatar,
              @Param("name") String name,@Param("intro") boolean intro,@Param("at") java.util.Date at);
    /** 保存已核验且带来源回合的待续话题。 */
    int verifiedResume(@Param("session") String session,@Param("turn") Long turn,@Param("zh") String zh,@Param("en") String en);
    /** 在话题完成后清除对应的待续状态。 */
    int finishTopic(@Param("session") String session,@Param("turn") Long turn);
}

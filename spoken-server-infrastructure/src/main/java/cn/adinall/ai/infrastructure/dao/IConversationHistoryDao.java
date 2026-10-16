package cn.adinall.ai.infrastructure.dao;

import cn.adinall.ai.infrastructure.dao.po.TrainingSessionPO;
import cn.adinall.ai.infrastructure.dao.po.ConversationTurnPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

/**
 * <b>功能</b>：会话档案的有界查询 SQL 接口。
 * <p><b>体系架构 / 运行过程</b></p>
 * <pre>
 * repository -> MyBatis mapper -> training_session / conversation_turn
 * </pre>
 * <p><b>案例</b>：userId=7, limit=21 -> 最多返回账号 7 的 21 场会话。</p>
 * <b>设计要点</b>
 * <ul><li>仅定义数据读写，不实现业务状态恢复。</li><li>翻页参数全部使用绑定变量。</li> </ul>
 */
@Mapper
public interface IConversationHistoryDao {
    /** 按账号与分页游标查询历史会话。 */
    List<TrainingSessionPO> sessions(@Param("userId") long userId,
            @Param("beforeTime") Long beforeTime, @Param("beforeId") String beforeId, @Param("limit") int limit);
    /** 读取指定会话的数据。 */
    TrainingSessionPO session(@Param("userId") long userId, @Param("sessionId") String sessionId);
    /** 按会话与回合游标读取对话记录。 */
    List<ConversationTurnPO> turns(@Param("userId") long userId, @Param("sessionId") String sessionId,
            @Param("afterId") long afterId, @Param("limit") int limit);
}

package cn.adinall.ai.domain.coaching.history.adapter.repository;

import cn.adinall.ai.domain.coaching.history.model.valobj.*;
import cn.adinall.ai.domain.coaching.conversation.model.aggregate.ConversationTurnAggregate;
import java.util.*;

/**
 * <b>功能</b>：按可信账号读取会话和聊天档案。
 * <p><b>体系架构 / 运行过程</b></p>
 * <pre>
 * history service -> repository -> durable sessions / turns
 * </pre>
 * <p><b>案例</b>：userId=7 查询用户 8 的 s-8 -> Optional.empty() / 空记录。</p>
 * <b>设计要点</b>
 * <ul><li>每条查询均包含 owner 条件。</li><li>不通过运行态仓储查询，关会话和重启不影响读取。</li> </ul>
 */
public interface IConversationHistoryRepository {
    /** 按账号与分页游标查询历史会话。 */
    List<SessionHistoryVO> sessions(long userId, SessionHistoryCursorVO before, int limit);
    /** 读取指定会话的数据。 */
    Optional<SessionHistoryVO> session(long userId, String sessionId);
    /** 按会话与回合游标读取对话记录。 */
    List<ConversationTurnAggregate> turns(long userId, String sessionId, long afterTurnId, int limit);
}

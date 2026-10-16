package cn.adinall.ai.infrastructure.adapter.repository;

import cn.adinall.ai.domain.coaching.history.adapter.repository.IConversationHistoryRepository;
import cn.adinall.ai.domain.coaching.history.model.valobj.*;
import cn.adinall.ai.domain.coaching.conversation.model.aggregate.ConversationTurnAggregate;
import cn.adinall.ai.infrastructure.dao.IConversationHistoryDao;
import cn.adinall.ai.infrastructure.dao.po.TrainingSessionPO;
import org.springframework.stereotype.Repository;
import java.util.*;

/**
 * <b>功能</b>：将带账号条件的持久档案查询映射为领域读模型。
 * <p><b>体系架构 / 运行过程</b></p>
 * <pre>
 * history service -> repository -> history DAO -> PO -> domain value
 * </pre>
 * <p><b>案例</b>：已关闭 s-1 在运行态被删除 -> 仍从 training_session 和 conversation_turn 返回档案。</p>
 * <b>设计要点</b>
 * <ul><li>复用现有 turnId，兼容评分和教学证据关联。</li><li>不将历史数据装载到活跃会话索引。</li> </ul>
 */
@Repository
public class ConversationHistoryRepository implements IConversationHistoryRepository {
    private final IConversationHistoryDao dao;
    /** 创建 {@code ConversationHistoryRepository}，初始化其依赖与状态。 */
    public ConversationHistoryRepository(IConversationHistoryDao dao) { this.dao = dao; }
    /** 按账号与分页游标查询历史会话。 */
    @Override public List<SessionHistoryVO> sessions(long userId, SessionHistoryCursorVO before, int limit) {
        return dao.sessions(userId, before == null ? null : before.startedAt(),
                before == null ? null : before.sessionId(), limit).stream().map(this::sessionValue).toList();
    }
    /** 读取指定会话的数据。 */
    @Override public Optional<SessionHistoryVO> session(long userId, String sessionId) {
        return Optional.ofNullable(dao.session(userId, sessionId)).map(this::sessionValue);
    }
    /** 按会话与回合游标读取对话记录。 */
    @Override public List<ConversationTurnAggregate> turns(long userId, String sessionId, long afterId, int limit) {
        return dao.turns(userId, sessionId, afterId, limit).stream().map(ConversationTurnRepository::toAggregate).toList();
    }
    /** 将数据库会话记录转换为历史查询值对象。 */
    private SessionHistoryVO sessionValue(TrainingSessionPO row) {
        return new SessionHistoryVO(row.getId(), row.getAvatarId(), row.getVoice(), row.getScenarioId(),
                row.getStatus(), row.getStartedAt() == null ? 0 : row.getStartedAt(), row.getEndedAt());
    }
}

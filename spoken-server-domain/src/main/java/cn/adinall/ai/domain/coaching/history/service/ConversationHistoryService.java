package cn.adinall.ai.domain.coaching.history.service;

import cn.adinall.ai.domain.coaching.history.model.valobj.SessionHistoryCursorVO;
import cn.adinall.ai.domain.coaching.history.model.valobj.SessionHistoryVO;
import java.util.List;

import cn.adinall.ai.domain.coaching.history.adapter.repository.IConversationHistoryRepository;
import cn.adinall.ai.domain.coaching.history.model.valobj.*;
import cn.adinall.ai.domain.coaching.conversation.model.aggregate.ConversationTurnAggregate;
import cn.adinall.ai.types.exception.AppException;
import org.springframework.stereotype.Service;

/**
 * <b>功能</b>：查询已持久化的会话和聊天记录，统一控制归属、翻页及结果上限。
 * <p><b>体系架构 / 运行过程</b></p>
 * <pre>
 * trusted account -> page validation -> owned repository query
 *                -> limit + 1 -> stable cursor page
 * </pre>
 * <p><b>案例</b>：账号 7 查询已关闭 s-1 的前 20 回合 -> 返回最多 20 条和 nextCursor；查询他人 s-2 -> session_not_found。</p>
 * <b>设计要点</b>
 * <ul><li>单领域读取直接由入口调用，无需经过 Case。</li><li>每页 1~100 条；不读取或重建实时会话。</li><li>无记录和无权限使用同一错误码，避免暴露他人资源。</li> </ul>
 */
@Service
public class ConversationHistoryService {
    private final IConversationHistoryRepository repository;
    /** 创建 {@code ConversationHistoryService}，初始化其依赖与状态。 */
    public ConversationHistoryService(IConversationHistoryRepository repository) { this.repository = repository; }

    /** 按账号与分页游标查询历史会话。 */
    public HistoryPageVO<SessionHistoryVO, SessionHistoryCursorVO> sessions(long userId,
            SessionHistoryCursorVO before, int limit) {
        validate(userId, limit);
        List<SessionHistoryVO> found = repository.sessions(userId, before, limit + 1);
        List<SessionHistoryVO> items = found.subList(0, Math.min(limit, found.size()));
        SessionHistoryVO last = items.isEmpty() ? null : items.get(items.size() - 1);
        SessionHistoryCursorVO cursor = found.size() > limit ? new SessionHistoryCursorVO(last.startedAt(), last.sessionId()) : null;
        return new HistoryPageVO<>(items, cursor);
    }

    /** 在账号范围内查找历史会话，查无记录时抛出业务异常。 */
    public SessionHistoryVO session(long userId, String sessionId) {
        validate(userId, 1);
        if (sessionId == null || sessionId.isBlank()) throw new IllegalArgumentException("invalid_session_id");
        return repository.session(userId, sessionId)
                .orElseThrow(() -> new AppException("session_not_found", "会话不存在或无权访问"));
    }

    /** 按会话与回合游标读取对话记录。 */
    public HistoryPageVO<ConversationTurnAggregate, Long> turns(long userId, String sessionId,
            long afterTurnId, int limit) {
        validate(userId, limit);
        if (afterTurnId < 0) throw new IllegalArgumentException("invalid_history_cursor");
        session(userId, sessionId);
        List<ConversationTurnAggregate> found = repository.turns(userId, sessionId, afterTurnId, limit + 1);
        List<ConversationTurnAggregate> items = found.subList(0, Math.min(limit, found.size()));
        Long cursor = found.size() > limit ? items.get(items.size() - 1).getId() : null;
        return new HistoryPageVO<>(items, cursor);
    }

    /** 校验账号标识与分页大小，拒绝非法查询范围。 */
    private static void validate(long userId, int limit) {
        if (userId <= 0) throw new AppException("unauthorized", "请先登录");
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("history_limit_must_be_1_to_100");
    }
}

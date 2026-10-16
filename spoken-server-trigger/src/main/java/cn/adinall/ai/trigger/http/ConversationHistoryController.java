package cn.adinall.ai.trigger.http;

import cn.adinall.ai.domain.coaching.history.model.valobj.HistoryPageVO;
import cn.adinall.ai.domain.coaching.history.model.valobj.SessionHistoryCursorVO;
import cn.adinall.ai.domain.coaching.history.model.valobj.SessionHistoryVO;

import cn.adinall.ai.api.dto.*;
import cn.adinall.ai.domain.coaching.history.service.ConversationHistoryService;
import cn.adinall.ai.domain.coaching.history.model.valobj.*;
import cn.adinall.ai.domain.coaching.conversation.model.aggregate.ConversationTurnAggregate;
import cn.adinall.ai.domain.identity.support.AccountContext;
import org.springframework.web.bind.annotation.*;

/**
 * <b>功能</b>：提供当前登录账号的持久会话列表、档案详情和分页聊天记录。
 * <p><b>体系架构 / 运行过程</b></p>
 * <pre>
 * Bearer token -> account interceptor -> trusted account
 *              -> history domain service -> owner-scoped SQL -> HTTP page
 * </pre>
 * <p><b>案例</b>：GET /api/v1/chat-history/sessions/s-1/turns?limit=20 -> items[0].userText=Hello；关闭会话后同样可查。</p>
 * <b>设计要点</b>
 * <ul><li>单领域只读调用直接进入 Domain。</li><li>不接收客户端 userId；管理后台权限应独立建模后开放。</li><li>聊天档案统一使用带游标的分页协议，旧 sessions/turns 入口已移除。</li></ul>
 */
@RestController
@RequestMapping("/api/v1/chat-history/sessions")
public class ConversationHistoryController {
    private final ConversationHistoryService history;
    /** 创建 {@code ConversationHistoryController}，初始化其依赖与状态。 */
    public ConversationHistoryController(ConversationHistoryService history) { this.history = history; }

    /** 按账号与分页游标查询历史会话。 */
    @GetMapping
    public HistoryPageResponseDTO<SessionHistoryResponseDTO, SessionHistoryCursorDTO> sessions(
            @RequestParam(required = false) Long beforeStartedAt,
            @RequestParam(required = false) String beforeSessionId,
            @RequestParam(defaultValue = "20") int limit) {
        if ((beforeStartedAt == null) != (beforeSessionId == null))
            throw new IllegalArgumentException("history_cursor_requires_time_and_session_id");
        SessionHistoryCursorVO cursor = beforeStartedAt == null ? null : new SessionHistoryCursorVO(beforeStartedAt, beforeSessionId);
        HistoryPageVO<SessionHistoryVO, SessionHistoryCursorVO> page = history.sessions(AccountContext.require().id(), cursor, limit);
        SessionHistoryCursorVO next = page.nextCursor();
        return new HistoryPageResponseDTO<>(page.items().stream().map(this::response).toList(),
                next == null ? null : new SessionHistoryCursorDTO(next.startedAt(), next.sessionId()));
    }

    /** 查询当前账号拥有的历史会话，越权访问按不存在处理。 */
    @GetMapping("/{sessionId}")
    public SessionHistoryResponseDTO session(@PathVariable String sessionId) {
        return response(history.session(AccountContext.require().id(), sessionId));
    }

    /** 按会话与回合游标读取对话记录。 */
    @GetMapping("/{sessionId}/turns")
    public HistoryPageResponseDTO<TurnResponseDTO, Long> turns(@PathVariable String sessionId,
            @RequestParam(defaultValue = "0") long afterTurnId, @RequestParam(defaultValue = "20") int limit) {
        HistoryPageVO<ConversationTurnAggregate, Long> page = history.turns(AccountContext.require().id(), sessionId, afterTurnId, limit);
        return new HistoryPageResponseDTO<>(page.items().stream().map(this::turn).toList(), page.nextCursor());
    }

    /** 将历史会话值对象转换为 HTTP 响应。 */
    private SessionHistoryResponseDTO response(SessionHistoryVO row) {
        return new SessionHistoryResponseDTO(row.sessionId(), row.avatarId(), row.voice(), row.scenarioId(),
                row.status(), row.startedAt(), row.endedAt());
    }
    /** 将对话回合转换为历史查询响应。 */
    private TurnResponseDTO turn(ConversationTurnAggregate row) {
        return new TurnResponseDTO(row.getId(), row.getSeq(), row.getUserText(), row.getAssistantText(),
                null, row.getLatencyMs(), row.getCreatedAt());
    }
}

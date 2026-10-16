package cn.adinall.ai.domain.coaching.history;

import cn.adinall.ai.domain.coaching.history.model.valobj.HistoryPageVO;
import cn.adinall.ai.domain.coaching.history.model.valobj.SessionHistoryCursorVO;
import cn.adinall.ai.domain.coaching.history.model.valobj.SessionHistoryVO;

import cn.adinall.ai.domain.coaching.history.service.ConversationHistoryService;
import cn.adinall.ai.domain.coaching.history.adapter.repository.IConversationHistoryRepository;
import cn.adinall.ai.domain.coaching.history.model.valobj.*;
import cn.adinall.ai.domain.coaching.conversation.model.aggregate.ConversationTurnAggregate;
import cn.adinall.ai.types.exception.AppException;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * <b>功能</b>：验证关闭后的档案读取、账号边界和稳定分页。
 * <p><b>体系架构 / 运行过程</b></p>
 * <pre>trusted account -> history service -> owner-scoped repository -> cursor assertions</pre>
 * <p><b>案例</b>：同毫秒的 s-c、s-b、s-a 请求两条 -> 返回 s-c、s-b，游标停在 s-b。</p>
 * <b>设计要点</b>
 * <ul><li>不装配实时媒体会话。</li><li>覆盖越权、无数据、非法页容量及下一页边界。</li></ul>
 */
public class ConversationHistoryTest {
    private SessionHistoryVO session(String id) {
        return new SessionHistoryVO(id, "a", "v", "free", "CLOSED", 1000, 2000L);
    }
    @Test public void stableCursorIncludesIdForSameTimestamp() {
        IConversationHistoryRepository repository = mock(IConversationHistoryRepository.class);
        when(repository.sessions(7, null, 3)).thenReturn(List.of(session("s-c"), session("s-b"), session("s-a")));
        ConversationHistoryService service = new ConversationHistoryService(repository);
        HistoryPageVO<SessionHistoryVO, SessionHistoryCursorVO> first = service.sessions(7, null, 2);
        assertEquals(2, first.items().size());
        assertEquals(new SessionHistoryCursorVO(1000, "s-b"), first.nextCursor());
        when(repository.sessions(7, first.nextCursor(), 3)).thenReturn(List.of(session("s-a")));
        HistoryPageVO<SessionHistoryVO, SessionHistoryCursorVO> last = service.sessions(7, first.nextCursor(), 2);
        assertEquals("s-a", last.items().get(0).sessionId());
        assertNull(last.nextCursor());
    }
    @Test public void closedSessionReadsTurnsWithoutRuntimeState() {
        IConversationHistoryRepository repository = mock(IConversationHistoryRepository.class);
        when(repository.session(7, "s-1")).thenReturn(Optional.of(session("s-1")));
        ConversationTurnAggregate turn = ConversationTurnAggregate.restore(42L,"s-1",0,"Hello","Hi",null,100L,1000L);
        when(repository.turns(7, "s-1",0, 2)).thenReturn(List.of(turn,turn));
        HistoryPageVO<ConversationTurnAggregate, Long> page = new ConversationHistoryService(repository).turns(7,"s-1",0,1);
        assertEquals("Hello", page.items().get(0).getUserText());
        assertEquals(Long.valueOf(42), page.nextCursor());
        verify(repository).turns(7,"s-1",0,2);
    }
    @Test public void inaccessibleSessionNeverLoadsTurns() {
        IConversationHistoryRepository repository = mock(IConversationHistoryRepository.class);
        ConversationHistoryService service = new ConversationHistoryService(repository);
        AppException error = assertThrows(AppException.class, () -> service.turns(7,"other",0,20));
        assertEquals("session_not_found", error.code());
        verify(repository, never()).turns(anyLong(), anyString(), anyLong(), anyInt());
    }
    @Test public void rejectsUnboundedAndMalformedQueriesBeforeReading() {
        IConversationHistoryRepository repository = mock(IConversationHistoryRepository.class);
        ConversationHistoryService service = new ConversationHistoryService(repository);
        assertThrows(IllegalArgumentException.class, () -> service.sessions(7,null,101));
        assertThrows(IllegalArgumentException.class, () -> service.turns(7,"s",-1,20));
        assertThrows(AppException.class, () -> service.sessions(0,null,20));
        assertThrows(IllegalArgumentException.class, () -> new SessionHistoryCursorVO(0,""));
        verifyNoInteractions(repository);
    }
}

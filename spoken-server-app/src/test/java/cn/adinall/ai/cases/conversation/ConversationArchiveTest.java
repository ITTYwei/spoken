package cn.adinall.ai.cases.conversation;

import cn.adinall.ai.domain.coaching.teaching.service.TeachingService;
import cn.adinall.ai.domain.coaching.conversation.adapter.port.IAvatarDriverPort;
import cn.adinall.ai.domain.coaching.conversation.adapter.repository.IConversationSessionRepository;
import cn.adinall.ai.domain.coaching.conversation.adapter.repository.IConversationTurnRepository;
import cn.adinall.ai.domain.coaching.conversation.model.aggregate.ConversationSessionAggregate;
import cn.adinall.ai.domain.coaching.conversation.model.aggregate.ConversationTurnAggregate;
import cn.adinall.ai.domain.coaching.conversation.model.valobj.ConversationEventVO;
import java.util.concurrent.CountDownLatch;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Sinks.Many;

import cn.adinall.ai.cases.conversation.runtime.ConversationServiceImpl;
import cn.adinall.ai.domain.coaching.conversation.adapter.port.*;
import cn.adinall.ai.domain.coaching.conversation.adapter.repository.*;
import cn.adinall.ai.domain.coaching.conversation.model.aggregate.*;
import cn.adinall.ai.domain.coaching.conversation.model.entity.*;
import cn.adinall.ai.domain.coaching.conversation.model.valobj.*;
import org.junit.Test;
import reactor.core.publisher.*;
import java.time.Duration;
import java.util.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * <b>功能</b>：验证聊天归档不串回合，且不依赖 HTTP SSE 订阅。
 * <p><b>体系架构 / 运行过程</b></p>
 * <pre>begin r1 on turn 10 -> user creates turn 11 -> r1 done -> update turn 10</pre>
 * <p><b>案例</b>：没有客户端订阅 events 时，驱动完成事件仍保存助手回复。</p>
 * <b>设计要点</b>
 * <ul><li>用本地事件流替身，不连接供应商。</li><li>校验重复回执不重复持久化及原 turnId 不被覆盖。</li></ul>
 */
public class ConversationArchiveTest {
    /** 每个测试独立持有教学运行态，并显式共享给相关应用协作者。 */
    private final TeachingService teachingStates = new TeachingService();

    @Test public void replyIsBoundBeforeTheNextUserTurnArrives() {
        IAvatarDriverPort driver = mock(IAvatarDriverPort.class);
        IConversationSessionRepository sessions = mock(IConversationSessionRepository.class);
        IConversationTurnRepository turns = mock(IConversationTurnRepository.class);
        ConversationSessionAggregate session = new ConversationSessionAggregate("s","p","d","a","m","v","free",SessionStatusEnum.ACTIVE);
        when(sessions.findById("s")).thenReturn(Optional.of(session));
        ConversationTurnAggregate first = ConversationTurnAggregate.restore(10L,"s",0,"Hi",null,null,null,1);
        ConversationTurnAggregate second = ConversationTurnAggregate.restore(11L,"s",1,"Next",null,null,null,2);
        when(turns.findLastBySessionId("s")).thenReturn(Optional.of(first), Optional.of(second));
        when(turns.findById(10L)).thenReturn(Optional.of(first));
        when(driver.streamReply(eq("p"), eq("r1"), eq("v"), any())).thenReturn(Mono.empty());
        ConversationServiceImpl service = cn.adinall.ai.cases.conversation.CaseTestFixture.runtime(driver,sessions,turns,mock(IConversationEventBusPort.class), teachingStates);
        service.streamReply("s","r1",Flux.just("Hello")).block();
        assertEquals(Long.valueOf(10), session.replyTurnId());
        ConversationEventVO done = ConversationEventVO.of(EventTypeEnum.ASSISTANT_DONE,Map.of("text","Hello","replyId","r1"));
        service.applyDriverEvent(session,done);
        service.applyDriverEvent(session,done);
        ArgumentCaptor<ConversationTurnAggregate> archived = org.mockito.ArgumentCaptor.forClass(ConversationTurnAggregate.class);
        verify(turns,times(1)).updateAssistantText(archived.capture());
        assertEquals(Long.valueOf(10), archived.getValue().getId());
        assertEquals("Hello", archived.getValue().getAssistantText());
        verify(turns,never()).findById(11L);
        assertNull(second.getAssistantText());
    }
    @Test public void proactiveScriptNeverConsumesAnUnansweredUserTurn() {
        IAvatarDriverPort driver = mock(IAvatarDriverPort.class);
        IConversationSessionRepository sessions = mock(IConversationSessionRepository.class);
        IConversationTurnRepository turns = mock(IConversationTurnRepository.class);
        ConversationSessionAggregate session = new ConversationSessionAggregate("s","p","d","a","m","v","free",SessionStatusEnum.ACTIVE);
        when(sessions.findById("s")).thenReturn(Optional.of(session));
        ConversationTurnAggregate user = ConversationTurnAggregate.restore(10L,"s",0,"My answer",null,null,null,1);
        when(turns.findLastBySessionId("s")).thenReturn(Optional.of(user));
        ConversationServiceImpl service = cn.adinall.ai.cases.conversation.CaseTestFixture.runtime(driver,sessions,turns,mock(IConversationEventBusPort.class), teachingStates);
        service.say("s", new SayCommandEntity("Let's practise", "v", "s:practice:2"));
        service.applyDriverEvent(session, ConversationEventVO.of(EventTypeEnum.ASSISTANT_DONE,
                Map.of("replyId","s:practice:2","text","Let's practise")));
        assertNull(user.getAssistantText());
        verify(turns,never()).updateAssistantText(any());
        ArgumentCaptor<ConversationTurnAggregate> saved = org.mockito.ArgumentCaptor.forClass(ConversationTurnAggregate.class);
        verify(turns).append(saved.capture());
        assertNull(saved.getValue().getUserText());
        assertEquals("Let's practise", saved.getValue().getAssistantText());
    }

    @Test public void providerEventsPersistWithoutAnyHttpSubscriber() throws Exception {
        IAvatarDriverPort driver = mock(IAvatarDriverPort.class);
        IConversationSessionRepository sessions = mock(IConversationSessionRepository.class);
        IConversationTurnRepository turns = mock(IConversationTurnRepository.class);
        Many<ConversationEventVO> sink = Sinks.many().unicast().<ConversationEventVO>onBackpressureBuffer();
        CountDownLatch subscribed = new java.util.concurrent.CountDownLatch(1);
        when(driver.createSession(any())).thenReturn(new SessionHandleVO("p",SessionStatusEnum.READY));
        when(driver.events("p")).thenReturn(sink.asFlux().doOnSubscribe(s -> subscribed.countDown()));
        when(turns.findLastBySessionId(anyString())).thenReturn(Optional.empty());
        ConversationServiceImpl service = cn.adinall.ai.cases.conversation.CaseTestFixture.runtime(driver,sessions,turns,mock(IConversationEventBusPort.class), teachingStates);
        ConversationSessionAggregate session = service.createSession(new StartConversationCommandEntity("d","a","m","v","free",null));
        assertTrue(subscribed.await(3, java.util.concurrent.TimeUnit.SECONDS));
        sink.tryEmitNext(ConversationEventVO.of(EventTypeEnum.ASSISTANT_DONE,"Hello"));
        verify(turns,timeout(2000)).append(any());
        verify(driver,times(1)).events("p");
        when(sessions.findById(session.getSessionId())).thenReturn(Optional.of(session));
        service.closeSession(session.getSessionId());
    }
}

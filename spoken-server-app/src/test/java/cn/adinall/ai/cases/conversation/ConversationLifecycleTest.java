package cn.adinall.ai.cases.conversation;

import cn.adinall.ai.domain.coaching.teaching.service.TeachingService;
import cn.adinall.ai.domain.coaching.teaching.model.aggregate.TeachingStateAggregate;
import cn.adinall.ai.domain.coaching.teaching.model.valobj.TeachingTaskVO;
import cn.adinall.ai.cases.conversation.runtime.ConversationRuntime;
import cn.adinall.ai.cases.conversation.runtime.ConversationServiceImpl;
import cn.adinall.ai.cases.conversation.runtime.IReplyCompletionPort;
import cn.adinall.ai.domain.coaching.conversation.adapter.port.IAvatarDriverPort;
import cn.adinall.ai.domain.coaching.conversation.adapter.port.IConversationEventBusPort;
import cn.adinall.ai.domain.coaching.conversation.adapter.repository.IConversationSessionRepository;
import cn.adinall.ai.domain.coaching.conversation.adapter.repository.IConversationTurnRepository;
import cn.adinall.ai.domain.coaching.conversation.model.aggregate.ConversationSessionAggregate;
import cn.adinall.ai.domain.coaching.conversation.model.valobj.ConversationEventVO;
import java.util.concurrent.CountDownLatch;
import reactor.core.Disposable.Swap;
import reactor.core.publisher.Sinks.Many;

import cn.adinall.ai.cases.conversation.runtime.*;
import cn.adinall.ai.cases.practice.PracticePlaybackCase;
import cn.adinall.ai.domain.coaching.conversation.adapter.port.*;
import cn.adinall.ai.domain.coaching.conversation.adapter.repository.*;
import cn.adinall.ai.domain.coaching.conversation.model.aggregate.*;
import cn.adinall.ai.domain.coaching.conversation.model.entity.StartConversationCommandEntity;
import cn.adinall.ai.domain.coaching.conversation.model.valobj.*;
import cn.adinall.ai.domain.coaching.practice.service.PracticeService;
import cn.adinall.ai.domain.learning.service.ILearnerLevelService;
import org.junit.Test;
import reactor.core.publisher.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** Every termination path must release all producers without any HTTP subscriber. */
public class ConversationLifecycleTest {
    /** 每个测试独立持有教学运行态，并显式共享给相关应用协作者。 */
    private final TeachingService teachingStates = new TeachingService();

    @Test public void providerCloseCancelsPreparationPlaybackAndDriverWithoutSse() throws Exception {
        IAvatarDriverPort driver=mock(IAvatarDriverPort.class);
        IConversationSessionRepository sessions=mock(IConversationSessionRepository.class);
        ConversationRuntime runtime=new ConversationRuntime();
        IConversationEventBusPort events=mock(IConversationEventBusPort.class);
        PracticeService practice=mock(PracticeService.class);
        ILearnerLevelService levels=mock(ILearnerLevelService.class);
        Many<ConversationEventVO> sink=Sinks.many().unicast().<ConversationEventVO>onBackpressureBuffer();
        CountDownLatch subscribed=new CountDownLatch(1);
        when(driver.createSession(any())).thenReturn(new SessionHandleVO("p",SessionStatusEnum.READY));
        when(driver.events("p")).thenReturn(sink.asFlux().doOnSubscribe(s -> subscribed.countDown()));
        IConversationTurnRepository turns=mock(IConversationTurnRepository.class);
        ConversationServiceImpl service=new ConversationServiceImpl(driver,sessions,turns,events,
                mock(ReplyArchiveCase.class),runtime,new PracticePlaybackCase(practice,events,sessions,levels, teachingStates),mock(IOpeningEncounterRepository.class), teachingStates);
        ConversationSessionAggregate session=service.createSession(new StartConversationCommandEntity("d","a","m","v","free",null));
        when(sessions.findById(session.getSessionId())).thenReturn(Optional.of(session));
        // 教学状态独立于会话对象，供应商主动关闭时仍须清空并释放。
        TeachingStateAggregate teachingState = teachingStates.state(session.getSessionId());
        teachingState.commit(TeachingTaskVO.question("What do you like?"));
        assertTrue(subscribed.await(3,TimeUnit.SECONDS));
        Swap round=runtime.reserve(session.getSessionId());
        Swap playback=runtime.playback(session.getSessionId());
        sink.tryEmitNext(ConversationEventVO.of(EventTypeEnum.SESSION_CLOSED,Map.of()));
        verify(sessions,timeout(2000)).remove(session.getSessionId());
        assertTrue(round.isDisposed()); assertTrue(playback.isDisposed());
        assertNull(runtime.events(session.getSessionId()));
        verify(practice).close(session.getSessionId());
        verify(levels).forgetSession(session.getSessionId());
        verify(events).remove(session.getSessionId());
        assertTrue(session.isFinished());
        assertEquals("", teachingState.pending().question());
        assertNotSame(teachingState, teachingStates.state(session.getSessionId()));
        // 供应商可能在关闭后发送没有 replyId 的尾部事件，不能再次进入归档或教学提交。
        ConversationEventVO late = service.applyDriverEvent(session,
                ConversationEventVO.of(EventTypeEnum.ASSISTANT_DONE, "What do you like?"));
        assertEquals(EventTypeEnum.HEARTBEAT, late.type());
    }

    @Test public void pollingAndExplicitCloseReleaseResourcesEvenWhenArchiveUpdateFails() {
        for (boolean explicit : List.of(false,true)) {
            IAvatarDriverPort driver=mock(IAvatarDriverPort.class);
            IConversationSessionRepository sessions=mock(IConversationSessionRepository.class);
            IConversationEventBusPort events=mock(IConversationEventBusPort.class);
            IReplyCompletionPort completion=mock(IReplyCompletionPort.class);
            ConversationRuntime runtime=new ConversationRuntime();
            ConversationSessionAggregate session=new ConversationSessionAggregate("s","p","d","a","m","v","free",SessionStatusEnum.ACTIVE);
            when(sessions.findById("s")).thenReturn(Optional.of(session));
            TeachingStateAggregate teachingState = teachingStates.state("s");
            teachingState.commit(TeachingTaskVO.question("What do you like?"));
            when(driver.status("p")).thenReturn(SessionStatusEnum.CLOSED);
            doThrow(new IllegalStateException("database unavailable")).when(sessions).update(session);
            ConversationServiceImpl service=new ConversationServiceImpl(driver,sessions,mock(IConversationTurnRepository.class),events,
                    mock(ReplyArchiveCase.class),runtime,completion,mock(IOpeningEncounterRepository.class), teachingStates);
            Swap round=runtime.reserve("s"); Swap playback=runtime.playback("s"); runtime.registerDriver("s");
            try { if(explicit) service.closeSession("s"); else service.refreshStatus(session); }
            catch(IllegalStateException expected) { assertTrue(explicit); }
            assertTrue(round.isDisposed()); assertTrue(playback.isDisposed()); assertNull(runtime.events("s"));
            // 归档失败也不能保留旧任务；下次重试关闭只处理归档，不恢复教学状态。
            assertEquals("", teachingState.pending().question());
            assertNotSame(teachingState, teachingStates.state("s"));
            verify(completion).closed("s"); verify(events).remove("s"); verify(sessions,never()).remove("s");
            doNothing().when(sessions).update(session);
            service.closeSession("s");
            verify(sessions).remove("s");
        }
    }
}

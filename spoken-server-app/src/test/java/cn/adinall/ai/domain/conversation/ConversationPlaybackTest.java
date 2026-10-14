package cn.adinall.ai.domain.conversation;

import cn.adinall.ai.domain.conversation.model.valobj.TeachingOutcomeEnum;

import cn.adinall.ai.domain.conversation.adapter.port.IAvatarDriverPort;
import cn.adinall.ai.domain.conversation.adapter.port.IConversationEventBusPort;
import cn.adinall.ai.domain.conversation.adapter.repository.IConversationSessionRepository;
import cn.adinall.ai.domain.conversation.adapter.repository.IConversationTurnRepository;
import cn.adinall.ai.domain.conversation.model.aggregate.ConversationSessionAggregate;
import cn.adinall.ai.domain.conversation.model.valobj.ConversationEventVO;
import cn.adinall.ai.domain.conversation.model.valobj.EventTypeEnum;
import cn.adinall.ai.domain.conversation.model.valobj.SessionStatusEnum;
import cn.adinall.ai.domain.conversation.service.impl.ConversationServiceImpl;
import cn.adinall.ai.infrastructure.gateway.OpenTalkingEventTranslator;
import org.junit.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 功能：验证播报取消、迟到事件隔离和真实首播事件，防止旧回复结束新回合。
 * <p>体系架构 / 运行过程：</p>
 * <pre>
 * streamReply -> driver/text subscription -> interrupt -> cancel
 * old reply event -> round check -> discard before persistence
 * </pre>
 * <p>案例：r1 已打断，迟到的 r1 done 不落库；speech.started 不触发出声状态。</p>
 * <p>设计要点：</p>
 * <ul><li>测试领域行为及对外事件协议，使用驱动替身。</li>
 * <li>覆盖文本结束后仍有待播媒体的阶段。</li></ul>
 */
public class ConversationPlaybackTest {
    @Test public void firstIntroductionIsRecordedOnlyAfterMediaDone() throws Exception {
        var session=new ConversationSessionAggregate("s","p","d","a","flashhead","Cherry","free",SessionStatusEnum.ACTIVE);
        String text=cn.adinall.ai.domain.conversation.service.opening.OpeningTemplates.render("FIRST",
                cn.adinall.ai.domain.learner.model.valobj.LearnerLevelEnum.LOW,0,null,false,null);
        session.freezeOpening(new cn.adinall.ai.domain.conversation.model.valobj.OpeningDecisionVO(java.time.LocalDate.now(),
                cn.adinall.ai.domain.learner.model.valobj.LearnerLevelEnum.LOW,"safe_default","v2",0,"FIRST","0","opening_v2",text,"none").withAvatar("a",null));
        session.markOpeningSpoken(); session.beginReply("s:opening");
        var driver=mock(IAvatarDriverPort.class);
        var encounter=mock(cn.adinall.ai.domain.conversation.adapter.repository.IOpeningEncounterRepository.class);
        var service=new ConversationServiceImpl(driver,mock(IConversationSessionRepository.class),mock(IConversationTurnRepository.class),mock(IConversationEventBusPort.class));
        var field=ConversationServiceImpl.class.getDeclaredField("openingEncounters"); field.setAccessible(true); field.set(service,encounter);
        verifyNoInteractions(encounter);
        when(driver.events("p")).thenReturn(Flux.just(ConversationEventVO.of(EventTypeEnum.ASSISTANT_DONE,Map.of("replyId","s:opening","text",text))));
        service.streamDriverEvents(session).collectList().block(Duration.ofSeconds(2));
        verify(encounter).recordShown(eq("device:d"),eq("s"),eq("a"),isNull(),eq(true),any());
        assertEquals("",session.teaching().pending().question());
        session.invalidateReply();
        service.streamDriverEvents(session).collectList().block(Duration.ofSeconds(2));
        verifyNoMoreInteractions(encounter);
    }
    @Test
    public void wholeReplyCommitsTaskOnlyAfterMediaDoneAndInterruptClearsIt() throws Exception {
        var session = new ConversationSessionAggregate("s", "p", "d", "anchor", "flashhead", "Cherry", "free", SessionStatusEnum.ACTIVE);
        var driver=mock(IAvatarDriverPort.class);
        var sessions=mock(IConversationSessionRepository.class);
        var replies=mock(cn.adinall.ai.domain.conversation.adapter.repository.ITeachingReplyRepository.class);
        var service=new ConversationServiceImpl(driver,sessions,mock(IConversationTurnRepository.class),mock(IConversationEventBusPort.class));
        var field=ConversationServiceImpl.class.getDeclaredField("teachingReplies"); field.setAccessible(true); field.set(service,replies);
        var task=cn.adinall.ai.domain.conversation.model.valobj.TeachingTaskVO.question("");
        var decision=new cn.adinall.ai.domain.conversation.model.valobj.TeachingDecisionVO(
                cn.adinall.ai.domain.learner.model.valobj.EnglishLevelEnum.L2,task,TeachingOutcomeEnum.RESPONDED,true,"",false,false);
        session.beginReply("r1");
        session.pendingTeaching(new cn.adinall.ai.domain.conversation.model.valobj.TeachingPlaybackVO(1L,"r1",decision));
        assertEquals("",session.teaching().pending().question());
        when(driver.events("p")).thenReturn(Flux.just(ConversationEventVO.of(EventTypeEnum.ASSISTANT_DONE,
                Map.of("replyId","r1","text","你好。你喜欢咖啡吗？"))));
        service.streamDriverEvents(session).collectList().block(Duration.ofSeconds(2));
        verify(replies).complete(1L,"r1","你好。你喜欢咖啡吗？");
        assertEquals("你喜欢咖啡吗？",session.teaching().pending().question());
        session.invalidateReply();
        assertNull(session.pendingTeaching());
        assertEquals("",session.teaching().pending().question());
    }

    @Test
    public void interruptCancelsPlaybackAndRejectsLateDone() {
        var session = new ConversationSessionAggregate("s", "p", "d", "anchor", "flashhead", "Cherry", "free", SessionStatusEnum.ACTIVE);
        var driver = mock(IAvatarDriverPort.class);
        var sessions = mock(IConversationSessionRepository.class);
        var turns = mock(IConversationTurnRepository.class);
        var bus = mock(IConversationEventBusPort.class);
        when(sessions.findById("s")).thenReturn(Optional.of(session));
        AtomicBoolean cancelled = new AtomicBoolean();
        when(driver.streamReply(eq("p"), eq("r1"), eq("Cherry"), any())).thenReturn(
                Mono.<Void>never().doOnCancel(() -> cancelled.set(true)));
        var service = new ConversationServiceImpl(driver, sessions, turns, bus);
        service.streamReply("s", "r1", Flux.just("Hi.")).subscribe();
        assertTrue(session.acceptsReply("r1"));
        service.interrupt("s");
        assertTrue(cancelled.get());
        assertFalse(session.acceptsReply("r1"));
        session.beginReply("r2");
        when(driver.events("p")).thenReturn(Flux.just(ConversationEventVO.of(EventTypeEnum.ASSISTANT_DONE,
                Map.of("replyId", "r1", "text", "old reply"))));
        assertTrue(service.streamDriverEvents(session).collectList().block(Duration.ofSeconds(2)).isEmpty());
        verifyNoInteractions(turns);
        verify(driver).interrupt("p");
    }

    @Test
    public void taskStartIsNotPlaybackAndMediaCarriesRound() {
        assertNull(OpenTalkingEventTranslator.translate("{\"event\":\"speech.started\",\"data\":{\"utterance_id\":\"r1\"}}"));
        var media = OpenTalkingEventTranslator.translate("{\"event\":\"speech.media_started\",\"data\":{\"utterance_id\":\"r1\"}}");
        assertEquals(EventTypeEnum.TTS_START, media.type());
        assertEquals("r1", ((Map<?, ?>) media.data()).get("replyId"));
    }
}

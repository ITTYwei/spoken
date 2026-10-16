package cn.adinall.ai.domain.coaching.conversation;

import cn.adinall.ai.domain.coaching.teaching.service.TeachingService;
import cn.adinall.ai.domain.coaching.conversation.adapter.repository.IOpeningEncounterRepository;
import cn.adinall.ai.domain.coaching.teaching.adapter.repository.ITeachingReplyRepository;
import cn.adinall.ai.domain.coaching.conversation.model.aggregate.ConversationTurnAggregate;
import cn.adinall.ai.domain.coaching.teaching.model.valobj.TeachingDecisionVO;
import cn.adinall.ai.domain.coaching.teaching.model.valobj.TeachingTaskVO;
import java.lang.reflect.Field;
import org.mockito.ArgumentCaptor;

import cn.adinall.ai.domain.coaching.teaching.model.valobj.TeachingOutcomeEnum;

import cn.adinall.ai.domain.coaching.conversation.adapter.port.IAvatarDriverPort;
import cn.adinall.ai.domain.coaching.conversation.adapter.port.IConversationEventBusPort;
import cn.adinall.ai.domain.coaching.conversation.adapter.repository.IConversationSessionRepository;
import cn.adinall.ai.domain.coaching.conversation.adapter.repository.IConversationTurnRepository;
import cn.adinall.ai.domain.coaching.conversation.model.aggregate.ConversationSessionAggregate;
import cn.adinall.ai.domain.coaching.conversation.model.valobj.ConversationEventVO;
import cn.adinall.ai.domain.coaching.conversation.model.valobj.EventTypeEnum;
import cn.adinall.ai.domain.coaching.conversation.model.valobj.SessionStatusEnum;
import cn.adinall.ai.cases.conversation.runtime.ConversationServiceImpl;
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
    /** 每个测试独立持有教学运行态，并显式共享给相关应用协作者。 */
    private final TeachingService teachingStates = new TeachingService();

    @Test public void firstIntroductionIsRecordedOnlyAfterMediaDone() throws Exception {
        ConversationSessionAggregate session=new ConversationSessionAggregate("s","p","d","a","flashhead","Cherry","free",SessionStatusEnum.ACTIVE);
        String text=cn.adinall.ai.domain.coaching.conversation.service.opening.OpeningTemplates.render("FIRST",
                cn.adinall.ai.domain.learning.model.valobj.LearnerLevelEnum.LOW,0,null,false,null);
        session.freezeOpening(new cn.adinall.ai.domain.coaching.conversation.model.valobj.OpeningDecisionVO(java.time.LocalDate.now(),
                cn.adinall.ai.domain.learning.model.valobj.LearnerLevelEnum.LOW,"safe_default","v2",0,"FIRST","0","opening_v2",text,"none").withAvatar("a",null));
        session.markOpeningSpoken(); session.beginReply("s:opening");
        IAvatarDriverPort driver=mock(IAvatarDriverPort.class);
        IOpeningEncounterRepository encounter=mock(cn.adinall.ai.domain.coaching.conversation.adapter.repository.IOpeningEncounterRepository.class);
        ConversationServiceImpl service=cn.adinall.ai.cases.conversation.CaseTestFixture.runtime(driver,mock(IConversationSessionRepository.class),mock(IConversationTurnRepository.class),mock(IConversationEventBusPort.class), teachingStates);
        Field field=ConversationServiceImpl.class.getDeclaredField("openingEncounters"); field.setAccessible(true); field.set(service,encounter);
        verifyNoInteractions(encounter);
        when(driver.events("p")).thenReturn(Flux.just(ConversationEventVO.of(EventTypeEnum.ASSISTANT_DONE,Map.of("replyId","s:opening","text",text))));
        service.streamDriverEvents(session).collectList().block(Duration.ofSeconds(2));
        verify(encounter).recordShown(eq("device:d"),eq("s"),eq("a"),isNull(),eq(true),any());
        assertEquals("",teachingStates.state(session.getSessionId()).pending().question());
        session.invalidateReply();
        service.streamDriverEvents(session).collectList().block(Duration.ofSeconds(2));
        verifyNoMoreInteractions(encounter);
    }
    @Test
    public void wholeReplyCommitsTaskOnlyAfterMediaDoneAndInterruptClearsIt() throws Exception {
        ConversationSessionAggregate session = new ConversationSessionAggregate("s", "p", "d", "anchor", "flashhead", "Cherry", "free", SessionStatusEnum.ACTIVE);
        IAvatarDriverPort driver=mock(IAvatarDriverPort.class);
        IConversationSessionRepository sessions=mock(IConversationSessionRepository.class);
        ITeachingReplyRepository replies=mock(cn.adinall.ai.domain.coaching.teaching.adapter.repository.ITeachingReplyRepository.class);
        ConversationServiceImpl service=cn.adinall.ai.cases.conversation.CaseTestFixture.runtime(driver,sessions,mock(IConversationTurnRepository.class),mock(IConversationEventBusPort.class), teachingStates);
        Field field=cn.adinall.ai.cases.conversation.runtime.ReplyArchiveCase.class.getDeclaredField("teaching"); field.setAccessible(true); field.set(org.springframework.test.util.ReflectionTestUtils.getField(service,"archive"),replies);
        TeachingTaskVO task=cn.adinall.ai.domain.coaching.teaching.model.valobj.TeachingTaskVO.question("");
        TeachingDecisionVO decision=new cn.adinall.ai.domain.coaching.teaching.model.valobj.TeachingDecisionVO(
                cn.adinall.ai.domain.learning.model.valobj.EnglishLevelEnum.L2,task,TeachingOutcomeEnum.RESPONDED,true,"",false,false);
        session.beginReply("r1");
        teachingStates.state(session.getSessionId()).pendingPlayback(new cn.adinall.ai.domain.coaching.teaching.model.valobj.TeachingPlaybackVO(1L,"r1",decision));
        assertEquals("",teachingStates.state(session.getSessionId()).pending().question());
        when(driver.events("p")).thenReturn(Flux.just(ConversationEventVO.of(EventTypeEnum.ASSISTANT_DONE,
                Map.of("replyId","r1","text","你好。你喜欢咖啡吗？"))));
        service.streamDriverEvents(session).collectList().block(Duration.ofSeconds(2));
        verify(replies).complete(1L,"r1","你好。你喜欢咖啡吗？");
        assertEquals("你喜欢咖啡吗？",teachingStates.state(session.getSessionId()).pending().question());
        when(sessions.findById("s")).thenReturn(Optional.of(session));
        service.interrupt("s");
        assertNull(teachingStates.state(session.getSessionId()).pendingPlayback());
        assertEquals("",teachingStates.state(session.getSessionId()).pending().question());
    }

    @Test
    public void interruptCancelsPlaybackAndRejectsLateDone() {
        ConversationSessionAggregate session = new ConversationSessionAggregate("s", "p", "d", "anchor", "flashhead", "Cherry", "free", SessionStatusEnum.ACTIVE);
        IAvatarDriverPort driver = mock(IAvatarDriverPort.class);
        IConversationSessionRepository sessions = mock(IConversationSessionRepository.class);
        IConversationTurnRepository turns = mock(IConversationTurnRepository.class);
        IConversationEventBusPort bus = mock(IConversationEventBusPort.class);
        when(sessions.findById("s")).thenReturn(Optional.of(session));
        AtomicBoolean cancelled = new AtomicBoolean();
        when(driver.streamReply(eq("p"), eq("r1"), eq("Cherry"), any())).thenReturn(
                Mono.<Void>never().doOnCancel(() -> cancelled.set(true)));
        ConversationServiceImpl service = cn.adinall.ai.cases.conversation.CaseTestFixture.runtime(driver, sessions, turns, bus, teachingStates);
        service.streamReply("s", "r1", Flux.just("Hi.")).subscribe();
        assertTrue(session.acceptsReply("r1"));
        service.interrupt("s");
        assertTrue(cancelled.get());
        assertFalse(session.acceptsReply("r1"));
        session.beginReply("r2");
        when(driver.events("p")).thenReturn(Flux.just(ConversationEventVO.of(EventTypeEnum.ASSISTANT_DONE,
                Map.of("replyId", "r1", "text", "old reply"))));
        assertTrue(service.streamDriverEvents(session).collectList().block(Duration.ofSeconds(2)).isEmpty());
        verify(turns, never()).append(any());
        verify(turns, never()).updateAssistantText(any());
        verify(driver).interrupt("p");
    }

    @Test
    public void taskStartIsNotPlaybackAndMediaCarriesRound() {
        assertNull(OpenTalkingEventTranslator.translate("{\"event\":\"speech.started\",\"data\":{\"utterance_id\":\"r1\"}}"));
        ConversationEventVO media = OpenTalkingEventTranslator.translate("{\"event\":\"speech.media_started\",\"data\":{\"utterance_id\":\"r1\"}}");
        assertEquals(EventTypeEnum.TTS_START, media.type());
        assertEquals("r1", ((Map<?, ?>) media.data()).get("replyId"));
    }
    /** 主动首句无用户回合，媒体完成照常登记模型生成目标，不能覆盖历史或持久化 null 回合。 */
    @Test public void proactiveGeneratedReadingCommitsOnlyAfterMediaAndNeverOverwritesLastUserTurn() throws Exception {
        ConversationSessionAggregate session = new ConversationSessionAggregate("s", "p", "d", "a", "m", "v", "free", SessionStatusEnum.ACTIVE);
        teachingStates.state(session.getSessionId()).initialize(cn.adinall.ai.domain.learning.model.valobj.EnglishLevelEnum.L2);
        teachingStates.state(session.getSessionId()).guidedReading(true); session.beginReply("reading-initial");
        TeachingDecisionVO decision = new cn.adinall.ai.domain.coaching.teaching.model.valobj.TeachingDecisionVO(
                cn.adinall.ai.domain.learning.model.valobj.EnglishLevelEnum.L2,
                new cn.adinall.ai.domain.coaching.teaching.model.valobj.TeachingTaskVO("READ", "", "", 0), TeachingOutcomeEnum.RESPONDED, true, "", false, true);
        teachingStates.state(session.getSessionId()).pendingPlayback(new cn.adinall.ai.domain.coaching.teaching.model.valobj.TeachingPlaybackVO(null, "reading-initial", decision));
        IAvatarDriverPort driver = mock(IAvatarDriverPort.class); IConversationTurnRepository turns = mock(IConversationTurnRepository.class);
        ITeachingReplyRepository replies = mock(cn.adinall.ai.domain.coaching.teaching.adapter.repository.ITeachingReplyRepository.class);
        ConversationServiceImpl service = cn.adinall.ai.cases.conversation.CaseTestFixture.runtime(driver, mock(IConversationSessionRepository.class), turns, mock(IConversationEventBusPort.class), teachingStates);
        Field field = cn.adinall.ai.cases.conversation.runtime.ReplyArchiveCase.class.getDeclaredField("teaching"); field.setAccessible(true); field.set(org.springframework.test.util.ReflectionTestUtils.getField(service,"archive"), replies);
        String generated = "想去公园可以说：“Let's go to the park.”，意思是我们去公园吧。你来试着读一读吧。";
        when(driver.events("p")).thenReturn(Flux.just(ConversationEventVO.of(EventTypeEnum.ASSISTANT_DONE,
                Map.of("replyId", "reading-initial", "text", generated))));
        assertEquals("", teachingStates.state(session.getSessionId()).readingMode().target());
        service.streamDriverEvents(session).collectList().block(Duration.ofSeconds(2));
        assertEquals("Let's go to the park.", teachingStates.state(session.getSessionId()).readingMode().target());
        verifyNoInteractions(replies);
        verify(turns, never()).updateAssistantText(any());
        ArgumentCaptor<ConversationTurnAggregate> saved = org.mockito.ArgumentCaptor.forClass(cn.adinall.ai.domain.coaching.conversation.model.aggregate.ConversationTurnAggregate.class);
        verify(turns).append(saved.capture());
        assertNull(saved.getValue().getUserText());
        assertEquals(generated, saved.getValue().getAssistantText());
    }

    @Test public void mediaDrainAndDuplicateDoneArchiveOnlyOnce() {
        ConversationSessionAggregate session = new ConversationSessionAggregate("s", "p", "d", "a", "m", "v", "free", SessionStatusEnum.ACTIVE);
        IAvatarDriverPort driver = mock(IAvatarDriverPort.class);
        IConversationSessionRepository sessions = mock(IConversationSessionRepository.class);
        IConversationTurnRepository turns = mock(IConversationTurnRepository.class);
        when(sessions.findById("s")).thenReturn(Optional.of(session));
        when(driver.streamReply(eq("p"), eq("r1"), eq("v"), any())).thenAnswer(invocation ->
                invocation.<Flux<String>>getArgument(3).then());
        ConversationServiceImpl service = cn.adinall.ai.cases.conversation.CaseTestFixture.runtime(driver, sessions, turns, mock(IConversationEventBusPort.class), teachingStates);
        service.streamReply("s", "r1", Flux.just("Hello", " world.")).block(Duration.ofSeconds(2));
        assertTrue(session.replyCompleted());
        when(driver.events("p")).thenReturn(Flux.just(ConversationEventVO.of(EventTypeEnum.ASSISTANT_DONE,
                Map.of("replyId", "r1", "text", "Hello world."))));
        service.streamDriverEvents(session).collectList().block(Duration.ofSeconds(2));
        ArgumentCaptor<ConversationTurnAggregate> saved = org.mockito.ArgumentCaptor.forClass(cn.adinall.ai.domain.coaching.conversation.model.aggregate.ConversationTurnAggregate.class);
        verify(turns).append(saved.capture());
        assertEquals("Hello world.", saved.getValue().getAssistantText());
    }
}

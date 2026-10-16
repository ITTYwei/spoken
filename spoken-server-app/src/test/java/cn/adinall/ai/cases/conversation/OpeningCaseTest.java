package cn.adinall.ai.cases.conversation;

import cn.adinall.ai.domain.coaching.teaching.service.TeachingService;
import cn.adinall.ai.cases.conversation.impl.OpeningPreparation;
import cn.adinall.ai.cases.teaching.TeachingPreparation;
import cn.adinall.ai.domain.coaching.conversation.model.valobj.OpeningDecisionVO;

import cn.adinall.ai.domain.learning.model.valobj.LearnerLevelEnum;

import cn.adinall.ai.cases.conversation.impl.*;
import cn.adinall.ai.domain.coaching.conversation.adapter.repository.IOpeningHistoryRepository;
import cn.adinall.ai.domain.coaching.conversation.adapter.port.*;
import cn.adinall.ai.domain.coaching.conversation.model.aggregate.ConversationSessionAggregate;
import cn.adinall.ai.domain.coaching.conversation.model.entity.*;
import cn.adinall.ai.domain.coaching.conversation.model.valobj.*;
import cn.adinall.ai.cases.conversation.runtime.IConversationService;
import cn.adinall.ai.domain.identity.adapter.port.IOpeningIdentityPort;
import cn.adinall.ai.domain.identity.model.valobj.OpeningSubjectVO;
import cn.adinall.ai.domain.coaching.practice.adapter.repository.IOpeningPlanRepository;
import cn.adinall.ai.domain.assessment.pronunciation.service.IPronunciationService;
import cn.adinall.ai.domain.content.adapter.repository.IScenarioRepository;
import cn.adinall.ai.domain.content.model.valobj.ScenarioVO;
import cn.adinall.ai.domain.shared.adapter.port.IAppConfigPort;
import cn.adinall.ai.cases.conversation.model.CreateConversationCommand;
import org.junit.Test;
import cn.adinall.ai.domain.companion.service.IChatService;
import cn.adinall.ai.domain.learning.service.level.LearnerLevelService;
import reactor.core.publisher.Flux;
import org.mockito.ArgumentCaptor;
import java.time.*;
import java.util.*;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 验证首句快照、脚本轨幂等和开场与第一轮档位一致。
 * <p>
 * 功能：验证首句快照、脚本轨幂等和开场与第一轮档位一致。
 * <p>
 * 体系架构 / 运行过程：
 * <pre>
 *   Mock 领域端口 -> 会话用例 -> 命令及调用次数断言
 * </pre>
 * 案例：
 * <pre>
 *   输入 -> 输出：重复 opening 两次 -> say 一次且 commandId=s1:opening
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>不调用真实模型。</li>
 *   <li>模拟跨午夜补写。</li>
 *   <li>创建命令必须携带决策。</li>
 * </ul>
 */
public class OpeningCaseTest {
    /** 每个测试独立持有教学运行态，并显式共享给相关应用协作者。 */
    private final TeachingService teachingStates = new TeachingService();

    private final IConversationService service=mock(IConversationService.class);
    private final IScenarioRepository scenarios=mock(IScenarioRepository.class);
    private final IPronunciationService pronunciation=mock(IPronunciationService.class);
    private final IAudioFormatPort audio=mock(IAudioFormatPort.class);
    private final IConversationEventBusPort events=mock(IConversationEventBusPort.class);
    private final IAppConfigPort config=mock(IAppConfigPort.class);
    private final OpeningPreparation preparation=mock(OpeningPreparation.class);
    private final IChatService chat=mock(IChatService.class);
    private final ConversationCaseImpl useCase=cn.adinall.ai.cases.conversation.CaseTestFixture.conversation(service,config,scenarios,pronunciation,audio,events,preparation,chat,new LearnerLevelService(),"100003", teachingStates);
    public OpeningCaseTest() {
        cn.adinall.ai.cases.conversation.CaseTestFixture.teaching(useCase, TeachingTestFixture.create(teachingStates));
        org.springframework.test.util.ReflectionTestUtils.setField(org.springframework.test.util.ReflectionTestUtils.getField(useCase,"replyGeneration"), "streamingEnabled",false);
    }
    private OpeningDecisionVO decision(String text) {
        return new OpeningDecisionVO(LocalDate.of(2026,10,12),LearnerLevelEnum.LOW,"recent_history","recent10_30d_v1",3,"DEFAULT","base","opening_v1",text,"samples=3");
    }
    private ConversationSessionAggregate session(String text) {
        ConversationSessionAggregate session=new ConversationSessionAggregate("s1","p1","d1",null,null,"Cherry","free",SessionStatusEnum.READY);
        session.freezeOpening(decision(text)); when(service.requireSession("s1")).thenReturn(session); return session;
    }
    @Test public void openingPlaysFrozenTextOnceWithoutRequeryingMaterials() {
        ConversationSessionAggregate session=session("你好，你今天做了什么？");
        useCase.opening("s1"); useCase.opening("s1");
        ArgumentCaptor<SayCommandEntity> command=ArgumentCaptor.forClass(SayCommandEntity.class);
        verify(service).say(eq("s1"),command.capture());
        assertEquals("你好，你今天做了什么？",command.getValue().text());
        assertEquals("s1:opening",command.getValue().commandId());
        assertTrue(session.isOpeningSpoken()); verifyNoInteractions(preparation,scenarios);
    }
    @Test public void noOpeningIsNotMarkedOrSent() {
        ConversationSessionAggregate session=session(null); useCase.opening("s1"); assertFalse(session.isOpeningSpoken());
        verify(service,never()).say(any(),any());
    }
    @Test public void failedOpeningSubmissionCanRetryWithoutRepeatingSuccessfulSubmission() {
        ConversationSessionAggregate session=session("你好，今天聊什么？");
        doThrow(new IllegalStateException("upstream unavailable")).doNothing().when(service).say(eq("s1"),any());
        try { useCase.opening("s1"); fail("Expected upstream failure"); } catch(IllegalStateException expected) {}
        assertFalse(session.isOpeningSpoken()); useCase.opening("s1"); useCase.opening("s1");
        verify(service,times(2)).say(eq("s1"),any());
    }
    @Test public void firstReplyUsesSameLowStartingLevelAsOpening() {
        session("你好，你今天做了什么？"); when(config.llmOwner()).thenReturn(LlmOwnerEnum.AGENT);
        byte[] pcm={1},wav={2}; when(audio.toWav(pcm,16000)).thenReturn(wav);
        when(service.transcribeUploadedAndRecord("s1",wav)).thenReturn(new UserUtteranceVO("Hi",1L));
        when(scenarios.findById("free")).thenReturn(new ScenarioVO("free","Free","Talk","Amy","advanced","Hi?","Cherry"));
        
        when(chat.handlePreparedTextStream(anyString(),anyString(),anyString(),anyString())).thenReturn(Flux.just("Hello?"));
        useCase.voice("s1",pcm,16000,null);
        ArgumentCaptor<String> prompt=ArgumentCaptor.forClass(String.class);
        verify(chat,timeout(3000)).handlePreparedTextStream(eq("100003"),eq("s1"),prompt.capture(), anyString());
        ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);
        verify(chat,timeout(3000)).handlePreparedTextStream(eq("100003"),eq("s1"),anyString(),system.capture());
        assertTrue(system.getValue().contains("当前等级：L2"));
        assertFalse(prompt.getValue().contains("<level_policy>"));
        assertFalse(prompt.getValue().contains("Amy"));
        verify(service,timeout(3000)).say(eq("s1"),any());
    }
    @Test public void snapshotCannotChangeAcrossMidnightOrNewDecision() {
        ConversationSessionAggregate session=session("你好，你今天做了什么？");
        OpeningDecisionVO next=new OpeningDecisionVO(LocalDate.of(2026,10,13),LearnerLevelEnum.HIGH,"scenario","v1",0,"FIRST","2","opening_v1","Hello, ready?","none");
        assertSame(session.getOpeningDecision(),session.freezeOpening(next));
        assertEquals(LocalDate.of(2026,10,12),session.getOpeningDecision().date());
    }
    @Test public void firstTurnPreservesGradeCapturedByOpening() {
        OpeningDecisionVO snapshot=decision("你好？").withTeachingLevel(cn.adinall.ai.domain.learning.model.valobj.EnglishLevelEnum.L4);
        ConversationSessionAggregate aggregate=new cn.adinall.ai.domain.coaching.conversation.model.aggregate.ConversationSessionAggregate(
                "frozen-grade","provider","d1","anchor","flashhead","Cherry","free",
                cn.adinall.ai.domain.coaching.conversation.model.valobj.SessionStatusEnum.ACTIVE);
        aggregate.freezeOpening(snapshot);
        TeachingPreparation teaching=TeachingTestFixture.create(teachingStates); // 存储替身返回L2，快照仍必须优先。
        teaching.bind(aggregate);
        assertEquals(cn.adinall.ai.domain.learning.model.valobj.EnglishLevelEnum.L4,teaching.level(aggregate));
    }
    @Test public void creationPassesDecisionIntoDomainCommand() {
        ScenarioVO scenario=new ScenarioVO("free","Free","Talk","Amy","beginner","Hi?","Cherry");
        when(scenarios.findById("free")).thenReturn(scenario); when(preparation.prepare(anyString(),eq(scenario),isNull())).thenReturn(decision("你好？"));
        ConversationSessionAggregate created=session("你好？");
        when(service.createSession(any())).thenReturn(created);
        useCase.create(new CreateConversationCommand("d1",null,null,null,"free"));
        ArgumentCaptor<StartConversationCommandEntity> command=ArgumentCaptor.forClass(StartConversationCommandEntity.class);
        verify(service).createSession(command.capture());
        assertTrue(command.getValue().deviceId().startsWith("u1:"));
        assertEquals("user:1",command.getValue().subject());
        assertEquals(LearnerLevelEnum.LOW,command.getValue().openingDecision().initialLevel());
    }
    @Test public void unavailableHistoryFallsBackWithoutCallingItFirst() {
        IOpeningHistoryRepository history=mock(IOpeningHistoryRepository.class); IOpeningPlanRepository plans=mock(IOpeningPlanRepository.class); IOpeningIdentityPort identity=mock(IOpeningIdentityPort.class);
        when(identity.currentSubject("d1")).thenReturn(new OpeningSubjectVO("d1",null,null));
        when(history.read(any(),any(),any())).thenThrow(new IllegalStateException("offline"));
        when(plans.findToday(any(),any(),any())).thenReturn(Optional.empty());
        OpeningPreparation prepared=CaseTestFixture.opening(history,plans,identity,Clock.fixed(Instant.parse("2026-10-12T09:00:00Z"),ZoneOffset.UTC));
        ScenarioVO scenario=new ScenarioVO("free","Free","Talk","Amy","beginner","Hi, ready?","Cherry");
        assertEquals("DEFAULT",prepared.prepare("d1",scenario).context());
    }
}

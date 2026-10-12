package cn.adinall.ai.cases.conversation;

import cn.adinall.ai.cases.conversation.impl.ConversationCaseImpl;
import cn.adinall.ai.domain.conversation.adapter.port.IAudioFormatPort;
import cn.adinall.ai.domain.conversation.adapter.port.IConversationEventBusPort;
import cn.adinall.ai.domain.conversation.model.aggregate.ConversationSessionAggregate;
import cn.adinall.ai.domain.conversation.model.valobj.*;
import cn.adinall.ai.domain.conversation.service.IConversationService;
import cn.adinall.ai.domain.pronunciation.model.aggregate.PronunciationAssessmentAggregate;
import cn.adinall.ai.domain.pronunciation.model.entity.AssessPronunciationCommandEntity;
import cn.adinall.ai.domain.pronunciation.model.valobj.PronunciationErrorVO;
import cn.adinall.ai.domain.pronunciation.model.valobj.PronunciationResultVO;
import cn.adinall.ai.domain.pronunciation.service.IPronunciationService;
import cn.adinall.ai.domain.scenario.adapter.repository.IScenarioRepository;
import cn.adinall.ai.domain.scenario.model.valobj.ScenarioVO;
import cn.adinall.ai.domain.shared.adapter.port.IAppConfigPort;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 验证跨域编排迁移后，评分降级、教学反馈与旧链路契约保持一致。 */
public class ConversationVoiceCaseTest {
    private final IConversationService conversation = mock(IConversationService.class);
    private final IAppConfigPort config = mock(IAppConfigPort.class);
    private final IScenarioRepository scenarios = mock(IScenarioRepository.class);
    private final IPronunciationService pronunciation = mock(IPronunciationService.class);
    private final IAudioFormatPort audio = mock(IAudioFormatPort.class);
    private final IConversationEventBusPort events = mock(IConversationEventBusPort.class);
    private final byte[] pcm = {1, 2};
    private final byte[] wav = {3, 4};
    private ConversationCaseImpl useCase;

    @Before
    public void setUp() {
        useCase = new ConversationCaseImpl(conversation, config, scenarios, pronunciation, audio, events);
        when(config.llmOwner()).thenReturn(LlmOwnerEnum.AGENT);
        when(conversation.requireSession("s1")).thenReturn(new ConversationSessionAggregate(
                "s1", "provider1", "device1", null, null, null, "advanced", SessionStatusEnum.ACTIVE));
        when(audio.toWav(pcm, 16000)).thenReturn(wav);
        when(conversation.transcribeUploadedAndRecord("s1", wav)).thenReturn(new UserUtteranceVO("Hello", 42L));
        when(scenarios.findById("advanced")).thenReturn(new ScenarioVO(
                "advanced", "Travel", "Talk", "Be Amy", "advanced", "Hi", "voice1"));
    }

    @Test
    public void mapsAssessmentIntoTeachingFeedbackAndPreservesResponse() {
        PronunciationResultVO result = mock(PronunciationResultVO.class);
        when(result.score()).thenReturn(86.0);
        when(result.errors()).thenReturn(List.of(new PronunciationErrorVO(
                1, "hello", "həˈloʊ", "helo", null, null, null, null)));
        PronunciationAssessmentAggregate assessment = mock(PronunciationAssessmentAggregate.class);
        when(assessment.getId()).thenReturn(7L);
        when(assessment.getTurnId()).thenReturn(42L);
        when(assessment.getResult()).thenReturn(result);
        when(pronunciation.assess(eq("s1"), eq(42L), any())).thenReturn(assessment);

        var response = useCase.voice("s1", pcm, 16000, "Read this");

        assertEquals("Hello", response.text());
        assertEquals(Long.valueOf(42), response.turnId());
        assertEquals(Long.valueOf(7), response.assessment().assessmentId());
        ArgumentCaptor<AssessPronunciationCommandEntity> command = ArgumentCaptor.forClass(AssessPronunciationCommandEntity.class);
        verify(pronunciation).assess(eq("s1"), eq(42L), command.capture());
        assertSame(wav, command.getValue().audio());
        assertEquals("Read this", command.getValue().expectedText());
        ArgumentCaptor<PronunciationFeedbackVO> feedback = ArgumentCaptor.forClass(PronunciationFeedbackVO.class);
        verify(conversation).replyForTurn(eq("s1"), eq(42L), eq("Hello"), feedback.capture(), eq(LearnerLevelEnum.HIGH), eq("Be Amy"));
        assertEquals(Double.valueOf(86), feedback.getValue().score());
        assertEquals("hello", feedback.getValue().errors().get(0).word());
        ArgumentCaptor<ConversationEventVO> event = ArgumentCaptor.forClass(ConversationEventVO.class);
        verify(events).publish(eq("s1"), event.capture());
        assertNotNull(event.getValue());
    }

    @Test
    public void assessmentFailureStillStartsReplyWithoutAssessmentCard() {
        when(pronunciation.assess(eq("s1"), eq(42L), any())).thenThrow(new IllegalStateException("provider down"));
        var response = useCase.voice("s1", pcm, 16000, null);
        assertNull(response.assessment());
        verify(conversation).replyForTurn("s1", 42L, "Hello", null, LearnerLevelEnum.HIGH, "Be Amy");
        ArgumentCaptor<AssessPronunciationCommandEntity> command = ArgumentCaptor.forClass(AssessPronunciationCommandEntity.class);
        verify(pronunciation).assess(eq("s1"), eq(42L), command.capture());
        assertEquals("Hello", command.getValue().expectedText());
        verifyNoInteractions(events);
    }

    @Test
    public void unrecognizableSpeechSkipsAssessmentAndStillStartsReply() {
        when(conversation.transcribeUploadedAndRecord("s1", wav)).thenReturn(new UserUtteranceVO("", 42L));
        when(scenarios.findById("advanced")).thenReturn(null);
        assertNull(useCase.voice("s1", pcm, 16000, " ").assessment());
        verifyNoInteractions(pronunciation);
        verify(conversation).replyForTurn("s1", 42L, "", null, LearnerLevelEnum.MID, null);
    }

    @Test
    public void legacyOwnerBypassesJavaAssessmentAndReply() {
        when(config.llmOwner()).thenReturn(LlmOwnerEnum.OPENTALKING);
        when(conversation.transcribeAndRecord("s1", pcm, 16000)).thenReturn("Legacy");
        var response = useCase.voice("s1", pcm, 16000, null);
        assertEquals("Legacy", response.text());
        assertNull(response.turnId());
        assertNull(response.assessment());
        verifyNoInteractions(audio, pronunciation, events);
        verify(conversation, never()).replyForTurn(anyString(), any(), any(), any(), any(), any());
    }
}

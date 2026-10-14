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
import cn.adinall.ai.domain.agent.service.IChatService;
import cn.adinall.ai.domain.learner.service.level.LearnerLevelService;
import cn.adinall.ai.domain.conversation.model.entity.SayCommandEntity;
import reactor.core.publisher.Flux;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 跨领域语音回复编排测试。
 * <p>功能：验证 case 调用 Agent 接口、整段播放、错误降级与旧链路隔离。
 * <p>体系架构 / 运行过程：
 * <pre>
 *   Mock 识别 / 评测 / Agent -> case -> 提示词、say、错误事件断言
 * </pre>
 * 案例：
 * <pre>
 *   输入：模型增量 Hi + there -> 输出：say 一次，文本为 Hi there。
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>等待异步完成信号后断言，不访问真实模型。</li>
 *   <li>覆盖建会话、生成、播放失败和无输出。</li>
 * </ul>
 */
public class ConversationVoiceCaseTest {
    private final IConversationService conversation = mock(IConversationService.class);
    private final IAppConfigPort config = mock(IAppConfigPort.class);
    private final IScenarioRepository scenarios = mock(IScenarioRepository.class);
    private final IPronunciationService pronunciation = mock(IPronunciationService.class);
    private final IAudioFormatPort audio = mock(IAudioFormatPort.class);
    private final IConversationEventBusPort events = mock(IConversationEventBusPort.class);
    private final IChatService chat = mock(IChatService.class);
    private final byte[] pcm = {1, 2};
    private final byte[] wav = {3, 4};
    private ConversationCaseImpl useCase;

    @Before
    public void setUp() {
        useCase = new ConversationCaseImpl(conversation, config, scenarios, pronunciation, audio, events,
                mock(cn.adinall.ai.cases.conversation.impl.OpeningPreparation.class), chat, new LearnerLevelService(), "100003");
        org.springframework.test.util.ReflectionTestUtils.setField(useCase, "streamingEnabled", false);
        org.springframework.test.util.ReflectionTestUtils.setField(useCase, "teaching", TeachingTestFixture.create());
        when(chat.handlePreparedTextStream(eq("100003"), eq("s1"), anyString(), anyString()))
                .thenReturn(Flux.just("Hi ", "there?"));
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
        assertEquals("Hello", command.getValue().expectedText()); // 客户端不能改服务端任务。
        String prompt = generatedPrompt();
        assertFalse(prompt.contains("86.0/100"));
        assertFalse(prompt.contains("həˈloʊ"));
        assertTrue(prompt.contains("学员产品档位：L2"));
        assertTrue(prompt.contains("Be Amy"));
        assertPlayedOnce();
        ArgumentCaptor<ConversationEventVO> event = ArgumentCaptor.forClass(ConversationEventVO.class);
        verify(events, atLeastOnce()).publish(eq("s1"), event.capture());
        assertNotNull(event.getValue());
    }

    @Test
    public void assessmentFailureStillStartsReplyWithoutAssessmentCard() {
        when(pronunciation.assess(eq("s1"), eq(42L), any())).thenThrow(new IllegalStateException("provider down"));
        var response = useCase.voice("s1", pcm, 16000, null);
        assertNull(response.assessment());
        assertTrue(generatedPrompt().contains("唯一教学动作："));
        assertPlayedOnce();
        ArgumentCaptor<AssessPronunciationCommandEntity> command = ArgumentCaptor.forClass(AssessPronunciationCommandEntity.class);
        verify(pronunciation).assess(eq("s1"), eq(42L), command.capture());
        assertEquals("Hello", command.getValue().expectedText());
        verify(events, never()).publish(anyString(), argThat(e -> e.type() == EventTypeEnum.ERROR || e.type() == EventTypeEnum.ASSESSMENT_DONE));
    }

    @Test
    public void unrecognizableSpeechSkipsAssessmentAndStillStartsReply() {
        when(conversation.transcribeUploadedAndRecord("s1", wav)).thenReturn(new UserUtteranceVO("", 42L));
        when(scenarios.findById("advanced")).thenReturn(null);
        assertNull(useCase.voice("s1", pcm, 16000, " ").assessment());
        verifyNoInteractions(pronunciation);
        ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);
        verify(chat, timeout(3000)).handlePreparedTextStream(eq("100003"), eq("s1"), anyString(), system.capture());
        assertTrue(system.getValue().contains("NO_AUDIO"));
        assertPlayedOnce();
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
        verifyNoInteractions(chat);
    }

    /** 等待异步调用完成并捕获传给 Agent 的业务提示词。 */
    private String generatedPrompt() {
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(chat, timeout(3000)).handlePreparedTextStream(eq("100003"), eq("s1"), prompt.capture(), anyString());
        return prompt.getValue();
    }

    /** 模型产生两段增量仍只下发一次完整文本。 */
    private void assertPlayedOnce() {
        ArgumentCaptor<SayCommandEntity> command = ArgumentCaptor.forClass(SayCommandEntity.class);
        verify(conversation, timeout(3000)).say(eq("s1"), command.capture());
        assertEquals("Hi there?", command.getValue().text());
        assertTrue(command.getValue().commandId().startsWith("s1:reply:42:"));
    }

    @Test
    public void modelFailurePublishesErrorWithoutPlayingOrClosing() {
        when(chat.handlePreparedTextStream(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Flux.concat(Flux.just("partial"), Flux.error(new IllegalStateException("model down"))));
        useCase.voice("s1", pcm, 16000, null);
        verify(events, timeout(3000)).publish(eq("s1"), argThat(e -> e.type() == EventTypeEnum.ERROR));
        verify(conversation, never()).say(anyString(), any());
        verify(conversation, never()).closeSession(anyString());
    }

    @Test
    public void sessionCreationFailureUsesAsyncErrorChannel() {
        when(chat.handlePreparedTextStream(anyString(), anyString(), anyString(), anyString())).thenThrow(new IllegalStateException("agent unavailable"));
        useCase.voice("s1", pcm, 16000, null);
        verify(events, timeout(3000)).publish(eq("s1"), argThat(e -> e.type() == EventTypeEnum.ERROR));
        verify(chat, never()).handleTextStream(anyString(), anyString(), anyString(), anyString());
        verify(conversation, never()).say(anyString(), any());
    }

    @Test
    public void emptyModelOutputDoesNotInsertCannedQuestion() throws Exception {
        java.util.concurrent.CountDownLatch completed = new java.util.concurrent.CountDownLatch(1);
        when(chat.handlePreparedTextStream(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Flux.<String>empty().doFinally(signal -> completed.countDown()));
        useCase.voice("s1", pcm, 16000, null);
        assertTrue(completed.await(3, java.util.concurrent.TimeUnit.SECONDS));
        verify(conversation, never()).say(eq("s1"), any());
        verify(events, never()).publish(anyString(), argThat(e -> e.type() == EventTypeEnum.ERROR || e.type() == EventTypeEnum.ASSESSMENT_DONE));
    }

    @Test
    public void playbackFailurePublishesErrorWithoutClosing() {
        doThrow(new IllegalStateException("say down")).when(conversation).say(anyString(), any());
        useCase.voice("s1", pcm, 16000, null);
        verify(events, timeout(3000)).publish(eq("s1"), argThat(e -> e.type() == EventTypeEnum.ERROR));
        verify(conversation, never()).closeSession(anyString());
    }
    @Test
    public void streamsBeforeCompletionAndInterruptCancelsAgent() throws Exception {
        org.springframework.test.util.ReflectionTestUtils.setField(useCase, "streamingEnabled", true);
        var source = reactor.core.publisher.Sinks.many().unicast().<String>onBackpressureBuffer();
        var received = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var cancelled = new java.util.concurrent.CountDownLatch(1);
        when(chat.handlePreparedTextStream(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(source.asFlux().doOnCancel(cancelled::countDown));
        when(conversation.streamReply(eq("s1"), anyString(), any())).thenAnswer(inv -> {
            Flux<String> text = inv.getArgument(2);
            return text.doOnNext(received::add).then();
        });
        useCase.voice("s1", pcm, 16000, null);
        source.tryEmitNext("First sentence.");
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
        while (received.isEmpty() && System.nanoTime() < deadline) Thread.sleep(10);
        // READ 直接透传增量，QUESTION 按句加分隔空格；两者都必须在完成前播出。
        assertEquals("First sentence.", String.join("", received).trim());
        verify(conversation, never()).say(anyString(), any());
        verify(events, never()).publish(anyString(), argThat(e -> e.type() == EventTypeEnum.TEXT_GENERATION_DONE));
        useCase.interrupt("s1");
        assertTrue(cancelled.await(3, java.util.concurrent.TimeUnit.SECONDS));
        source.tryEmitNext("Late old reply.");
        assertEquals(1, received.size());
    }

    @Test
    public void interruptDuringAssessmentPreventsLateReplyStart() {
        when(pronunciation.assess(eq("s1"), eq(42L), any())).thenAnswer(inv -> {
            useCase.interrupt("s1");
            return null;
        });
        useCase.voice("s1", pcm, 16000, null);
        verify(chat, never()).handlePreparedTextStream(anyString(), anyString(), anyString(), anyString());
        verify(conversation, never()).streamReply(anyString(), anyString(), any());
    }

}

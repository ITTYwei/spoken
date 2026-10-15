package cn.adinall.ai.cases.conversation;

import cn.adinall.ai.cases.conversation.impl.GuidedReadingCase;
import cn.adinall.ai.domain.account.model.valobj.AccountPrincipalVO;
import cn.adinall.ai.domain.account.support.AccountContext;
import cn.adinall.ai.domain.conversation.adapter.port.*;
import cn.adinall.ai.domain.conversation.adapter.repository.*;
import cn.adinall.ai.domain.conversation.model.aggregate.ConversationSessionAggregate;
import cn.adinall.ai.domain.conversation.model.entity.SayCommandEntity;
import cn.adinall.ai.domain.conversation.model.valobj.*;
import cn.adinall.ai.domain.conversation.service.IConversationService;
import cn.adinall.ai.domain.conversation.service.impl.ConversationServiceImpl;
import cn.adinall.ai.domain.conversation.service.reply.GuidedReadingPolicy;

import cn.adinall.ai.types.exception.AppException;
import org.junit.*;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;
import java.time.Duration;
import java.util.Map;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 跟读按钮与真实媒体完成链路测试，不调用在线模型、评测服务或数据库。 */
public class GuidedReadingCaseTest {
    private final IConversationService conversations = mock(IConversationService.class);
    private final IConversationCase cancellation = mock(IConversationCase.class);
    private final IConversationEventBusPort events = mock(IConversationEventBusPort.class);
    private final ConversationSessionAggregate session = new ConversationSessionAggregate("s", "p", "d", null, null, "voice", "free", SessionStatusEnum.ACTIVE);
    private final cn.adinall.ai.domain.shared.adapter.port.IAppConfigPort config = mock(cn.adinall.ai.domain.shared.adapter.port.IAppConfigPort.class);
    private final GuidedReadingCase useCase = new GuidedReadingCase(conversations, cancellation, TeachingTestFixture.create(), events, config);

    @Before public void setup() {
        session.bindTeachingSubject("user:7");
        when(config.llmOwner()).thenReturn(LlmOwnerEnum.AGENT);
        when(conversations.requireSession("s")).thenReturn(session);
        AccountContext.bind(new AccountPrincipalVO(7, "u7", "test"));
    }
    @After public void cleanup() { AccountContext.clear(); }

    @Test public void openingIsConversationalAndRepeatedEnableIsIdempotent() {
        assertTrue(useCase.change("s", true).enabled());
        assertEquals("", useCase.get("s").target());
        useCase.change("s", true);
        verify(cancellation, times(1)).interrupt("s");
        var command = ArgumentCaptor.forClass(SayCommandEntity.class);
        verify(conversations, times(1)).say(eq("s"), command.capture());
        assertTrue(command.getValue().text().contains("意思是"));
        assertTrue(command.getValue().text().contains("How are you?"));
        verify(conversations, never()).recordUserText(anyString(), anyString());
        assertTrue(session.teaching().completeReadingOpening(command.getValue().commandId(), command.getValue().text()));
        assertEquals("How are you?", useCase.get("s").target());
    }

    @Test public void sayFailureRollsBackModeWithoutLeavingFakeTarget() {
        doThrow(new IllegalStateException("down")).when(conversations).say(anyString(), any());
        try { useCase.change("s", true); fail(); } catch (IllegalStateException expected) { }
        assertFalse(useCase.get("s").enabled());
        assertEquals("", useCase.get("s").target());
    }

    @Test public void otherAccountsCannotReadOrSwitchMode() {
        AccountContext.bind(new AccountPrincipalVO(8, "u8", "other"));
        try { useCase.change("s", true); fail(); } catch (AppException expected) { }
        try { useCase.get("s"); fail(); } catch (AppException expected) { }
        verifyNoInteractions(cancellation);
        verify(conversations, never()).say(anyString(), any());
    }

    @Test public void disableClearsTargetAndRejectsLateIntroduction() {
        useCase.change("s", true);
        var command = ArgumentCaptor.forClass(SayCommandEntity.class);
        verify(conversations).say(eq("s"), command.capture());
        var old = command.getValue();
        useCase.change("s", false);
        assertFalse(session.teaching().completeReadingOpening(old.commandId(), old.text()));
        assertEquals("", useCase.get("s").target());
    }

    @Test public void actualDriverDoneCommitsScriptTargetAndPublishesSnapshot() {
        useCase.change("s", true);
        var command = ArgumentCaptor.forClass(SayCommandEntity.class);
        verify(conversations).say(eq("s"), command.capture());
        var intro = command.getValue();
        session.beginReply(intro.commandId());
        var driver = mock(IAvatarDriverPort.class);
        var bus = mock(IConversationEventBusPort.class);
        var service = new ConversationServiceImpl(driver, mock(IConversationSessionRepository.class), mock(IConversationTurnRepository.class), bus);
        when(driver.events("p")).thenReturn(Flux.just(ConversationEventVO.of(EventTypeEnum.ASSISTANT_DONE,
                Map.of("replyId", intro.commandId(), "text", intro.text()))));
        service.streamDriverEvents(session).collectList().block(Duration.ofSeconds(2));
        assertEquals("How are you?", session.teaching().pending().target());
        verify(bus).publish(eq("s"), argThat(e -> e.type() == EventTypeEnum.READING_CHANGED
                && ((ReadingModeVO)e.data()).target().equals("How are you?")));
    }
}

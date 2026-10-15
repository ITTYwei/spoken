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

    @Test public void openingRequestsExistingAgentWithoutScriptedSentenceOrFakeUserTurn() {
        assertTrue(useCase.change("s", true).enabled()); assertEquals("", useCase.get("s").target());
        useCase.change("s", true);
        verify(cancellation, times(1)).interrupt("s"); verify(cancellation, times(1)).startGuidedReading("s");
        verify(conversations, never()).say(anyString(), any()); verify(conversations, never()).recordUserText(anyString(), anyString());
    }
    @Test public void failureToStartRollsBackMode() {
        doThrow(new IllegalStateException("down")).when(cancellation).startGuidedReading("s");
        try { useCase.change("s", true); fail(); } catch (IllegalStateException expected) { }
        assertFalse(useCase.get("s").enabled()); assertEquals("", useCase.get("s").target());
    }
    @Test public void otherAccountsCannotReadOrSwitchMode() {
        AccountContext.bind(new AccountPrincipalVO(8, "u8", "other"));
        try { useCase.change("s", true); fail(); } catch (AppException expected) { }
        try { useCase.get("s"); fail(); } catch (AppException expected) { }
        verifyNoInteractions(cancellation);
    }
    @Test public void disableClearsCurrentTargetButKeepsRecentlyPractisedSentences() {
        useCase.change("s", true);
        var d = new TeachingDecisionVO(session.teaching().level(), new TeachingTaskVO("READ", "", "", 0), TeachingOutcomeEnum.RESPONDED, true, "", false, true);
        session.teaching().commitSpoken(d, "介绍早餐时可以说：“I'd like some toast.”，意思是我想要些吐司。你来试着读一读吧。");
        useCase.change("s", false); assertEquals("", useCase.get("s").target());
        assertEquals(java.util.List.of("I'd like some toast."), session.teaching().recentReadingTargets());
    }
}

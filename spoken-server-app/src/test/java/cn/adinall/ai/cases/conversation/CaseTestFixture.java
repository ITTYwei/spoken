package cn.adinall.ai.cases.conversation;

import cn.adinall.ai.domain.coaching.teaching.service.TeachingService;
import cn.adinall.ai.cases.conversation.impl.AgentReplyPreparation;
import cn.adinall.ai.cases.conversation.impl.ReplyGenerationCase;
import cn.adinall.ai.cases.teaching.TeachingPreparation;
import cn.adinall.ai.cases.conversation.runtime.ConversationRuntime;
import cn.adinall.ai.domain.coaching.conversation.adapter.port.IOpeningAvatarPort;
import cn.adinall.ai.domain.coaching.conversation.adapter.repository.IOpeningEncounterRepository;
import cn.adinall.ai.domain.coaching.conversation.adapter.repository.IReplyReceiptRepository;

import cn.adinall.ai.cases.conversation.impl.*;
import cn.adinall.ai.cases.conversation.runtime.*;
import cn.adinall.ai.cases.practice.PracticeCase;
import cn.adinall.ai.cases.security.*;
import cn.adinall.ai.domain.coaching.conversation.adapter.port.*;
import cn.adinall.ai.domain.coaching.conversation.adapter.repository.*;
import cn.adinall.ai.domain.coaching.teaching.adapter.repository.ITeachingReplyRepository;
import cn.adinall.ai.domain.coaching.conversation.model.aggregate.ConversationSessionAggregate;
import cn.adinall.ai.domain.coaching.practice.service.PracticeService;
import cn.adinall.ai.domain.companion.service.IChatService;
import cn.adinall.ai.domain.assessment.pronunciation.service.IPronunciationService;
import cn.adinall.ai.domain.content.adapter.repository.IScenarioRepository;
import cn.adinall.ai.domain.identity.model.valobj.AccountPrincipalVO;
import cn.adinall.ai.domain.identity.support.AccountContext;
import cn.adinall.ai.domain.learning.service.ILearnerLevelService;
import cn.adinall.ai.domain.shared.adapter.port.IAppConfigPort;
import static org.mockito.Mockito.*;

/** Explicit substitutes for required production collaborators. */
public final class CaseTestFixture {
    private CaseTestFixture() { }
    public static ICurrentActor actors() {
        return () -> AccountContext.current() == null ? new AccountPrincipalVO(1, "u1", "test") : AccountContext.require();
    }
    public static OpeningPreparation opening(IOpeningHistoryRepository history,
            cn.adinall.ai.domain.coaching.practice.adapter.repository.IOpeningPlanRepository plans,
            cn.adinall.ai.domain.identity.adapter.port.IOpeningIdentityPort identity, java.time.Clock clock) {
        TeachingPreparation teaching=mock(TeachingPreparation.class);
        when(teaching.initial(anyString(),anyString())).thenReturn(cn.adinall.ai.domain.learning.model.valobj.EnglishLevelEnum.L2);
        IOpeningEncounterRepository encounters=mock(IOpeningEncounterRepository.class);
        when(encounters.read(anyString())).thenReturn(cn.adinall.ai.domain.coaching.conversation.model.valobj.OpeningEncounterVO.unseen());
        IOpeningAvatarPort avatars=mock(IOpeningAvatarPort.class);
        when(avatars.effectiveId(any())).thenAnswer(i -> i.getArgument(0));
        return new OpeningPreparation(history,plans,identity,clock,teaching,encounters,avatars);
    }
    public static void teaching(ConversationCaseImpl useCase, TeachingPreparation teaching) {
        org.springframework.test.util.ReflectionTestUtils.setField(useCase,"teaching",teaching);
        org.springframework.test.util.ReflectionTestUtils.setField(org.springframework.test.util.ReflectionTestUtils.getField(useCase,"replyGeneration"),"teaching",teaching);
    }
    public static ConversationServiceImpl runtime(IAvatarDriverPort driver, IConversationSessionRepository sessions,
            IConversationTurnRepository turns, IConversationEventBusPort events, TeachingService teachingStates) {
        IReplyReceiptRepository receipts = mock(IReplyReceiptRepository.class);
        when(receipts.claim(anyString(), anyString())).thenReturn(true);
        return new ConversationServiceImpl(driver, sessions, turns, events,
                new ReplyArchiveCase(turns, mock(ITeachingReplyRepository.class), receipts, teachingStates), new ConversationRuntime(),
                mock(IReplyCompletionPort.class), mock(IOpeningEncounterRepository.class), teachingStates);
    }
    public static ConversationCaseImpl conversation(IConversationService service, IAppConfigPort config,
            IScenarioRepository scenarios, IPronunciationService pronunciation, IAudioFormatPort audio,
            IConversationEventBusPort events, OpeningPreparation opening, IChatService chat,
            ILearnerLevelService levels, String agent, TeachingService teachingStates) {
        PracticeCase courses = mock(PracticeCase.class);
        when(courses.opening(any(), any())).thenAnswer(i -> i.getArgument(1));
        when(courses.openingForPlayback(any(), any())).thenAnswer(i -> i.getArgument(1));
        PracticeService practice = mock(PracticeService.class);
        when(practice.state(anyString())).thenReturn(mock(cn.adinall.ai.domain.coaching.practice.model.aggregate.PracticeAggregate.class));
        AgentReplyPreparation replies = mock(AgentReplyPreparation.class);
        when(replies.reply(anyString(), any(), anyLong(), any(), anyString(), anyString(), any(), any()))
                .thenAnswer(i -> chat.handlePreparedTextStream(i.getArgument(0),
                        ((ConversationSessionAggregate)i.getArgument(1)).getSessionId(), i.getArgument(4), i.getArgument(5)));
        ConversationRuntime runtime = new ConversationRuntime();
        TeachingPreparation teaching=TeachingTestFixture.create(teachingStates);
        ReplyGenerationCase generation=new ReplyGenerationCase(service, runtime, events, teaching, replies, agent);
        return new ConversationCaseImpl(service, config, scenarios, pronunciation, audio, events, opening, levels,
                teaching, generation, courses, practice, runtime,
                new ReplyCancellationCase(service, practice, events, runtime, teachingStates), mock(SessionAccess.class), actors());
    }
}

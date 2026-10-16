package cn.adinall.ai.cases.conversation;

import cn.adinall.ai.domain.coaching.teaching.service.TeachingService;
import cn.adinall.ai.cases.practice.PracticeCase;
import cn.adinall.ai.domain.coaching.practice.adapter.repository.IRecentLessonRepository;
import cn.adinall.ai.domain.coaching.practice.service.PracticeService;
import cn.adinall.ai.domain.content.model.valobj.TextLessonVO;
import cn.adinall.ai.domain.content.service.TextbookService;

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

/** 详情直接开始：验证真实选课状态、课程首句、幂等与继续课程任务的边界，不连接媒体。 */
public class CourseOpeningCaseTest {
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
    public CourseOpeningCaseTest() {
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
    /** 待机选课先落状态，实际开场只播所选课首题；重复开始及旧继续课邀请均不能追加播报。 */
    @Test public void courseSelectedBeforeOpeningReplacesFrozenFreeGreeting() {
        ConversationSessionAggregate session = session("要继续上次课程，还是自由聊聊？");
        IRecentLessonRepository recent = mock(cn.adinall.ai.domain.coaching.practice.adapter.repository.IRecentLessonRepository.class);
        PracticeService practice = new cn.adinall.ai.domain.coaching.practice.service.PracticeService(recent);
        TextbookService books = new cn.adinall.ai.domain.content.service.TextbookService(
                new cn.adinall.ai.infrastructure.adapter.repository.ResourceTextbookRepository(new com.fasterxml.jackson.databind.ObjectMapper()));
        PracticeCase courses = new cn.adinall.ai.cases.practice.PracticeCase(service, practice, books, events, mock(cn.adinall.ai.cases.conversation.runtime.ReplyCancellationCase.class), mock(cn.adinall.ai.cases.security.SessionAccess.class), teachingStates);
        TextLessonVO lesson = books.list().get(0).lessons().get(0);
        practice.initialize("s1", "bandready-daily", "food-cooking", "食物与烹饪");
        practice.state("s1").change("COURSE", "bandready-daily", lesson.id(), lesson.title(), "chosen", "chosen");
        org.springframework.test.util.ReflectionTestUtils.setField(useCase, "practiceCase", courses);
        useCase.opening("s1"); useCase.opening("s1");
        ArgumentCaptor<SayCommandEntity> command = ArgumentCaptor.forClass(SayCommandEntity.class);
        verify(service).say(eq("s1"), command.capture());
        assertTrue(command.getValue().text().contains(lesson.questions().get(0)));
        assertFalse(command.getValue().text().contains("还是自由聊聊"));
        assertEquals("s1:opening", command.getValue().commandId());
        assertFalse(practice.state("s1").heard("s1:opening"));
        assertEquals(lesson.id(), practice.state("s1").snapshot().lessonId());
        assertEquals("要继续上次课程，还是自由聊聊？", session.getOpeningDecision().text());
    }
}

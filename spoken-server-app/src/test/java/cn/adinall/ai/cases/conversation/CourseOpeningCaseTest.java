package cn.adinall.ai.cases.conversation;

import cn.adinall.ai.domain.learner.model.valobj.LearnerLevelEnum;

import cn.adinall.ai.cases.conversation.impl.*;
import cn.adinall.ai.domain.conversation.adapter.repository.IOpeningHistoryRepository;
import cn.adinall.ai.domain.conversation.adapter.port.*;
import cn.adinall.ai.domain.conversation.model.aggregate.ConversationSessionAggregate;
import cn.adinall.ai.domain.conversation.model.entity.*;
import cn.adinall.ai.domain.conversation.model.valobj.*;
import cn.adinall.ai.domain.conversation.service.IConversationService;
import cn.adinall.ai.domain.identity.adapter.port.IOpeningIdentityPort;
import cn.adinall.ai.domain.identity.model.valobj.OpeningSubjectVO;
import cn.adinall.ai.domain.practice.adapter.repository.IOpeningPlanRepository;
import cn.adinall.ai.domain.pronunciation.service.IPronunciationService;
import cn.adinall.ai.domain.scenario.adapter.repository.IScenarioRepository;
import cn.adinall.ai.domain.scenario.model.valobj.ScenarioVO;
import cn.adinall.ai.domain.shared.adapter.port.IAppConfigPort;
import cn.adinall.ai.api.dto.CreateConversationSessionRequestDTO;
import org.junit.Test;
import cn.adinall.ai.domain.agent.service.IChatService;
import cn.adinall.ai.domain.learner.service.level.LearnerLevelService;
import reactor.core.publisher.Flux;
import org.mockito.ArgumentCaptor;
import java.time.*;
import java.util.*;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 详情直接开始：验证真实选课状态、课程首句、幂等与继续课程任务的边界，不连接媒体。 */
public class CourseOpeningCaseTest {
    private final IConversationService service=mock(IConversationService.class);
    private final IScenarioRepository scenarios=mock(IScenarioRepository.class);
    private final IPronunciationService pronunciation=mock(IPronunciationService.class);
    private final IAudioFormatPort audio=mock(IAudioFormatPort.class);
    private final IConversationEventBusPort events=mock(IConversationEventBusPort.class);
    private final IAppConfigPort config=mock(IAppConfigPort.class);
    private final OpeningPreparation preparation=mock(OpeningPreparation.class);
    private final IChatService chat=mock(IChatService.class);
    private final ConversationCaseImpl useCase=new ConversationCaseImpl(service,config,scenarios,pronunciation,audio,events,preparation,chat,new LearnerLevelService(),"100003");
    public CourseOpeningCaseTest() {
        org.springframework.test.util.ReflectionTestUtils.setField(useCase,"teaching",TeachingTestFixture.create());
        org.springframework.test.util.ReflectionTestUtils.setField(useCase,"streamingEnabled",false);
    }
    private OpeningDecisionVO decision(String text) {
        return new OpeningDecisionVO(LocalDate.of(2026,10,12),LearnerLevelEnum.LOW,"recent_history","recent10_30d_v1",3,"DEFAULT","base","opening_v1",text,"samples=3");
    }
    private ConversationSessionAggregate session(String text) {
        var session=new ConversationSessionAggregate("s1","p1","d1",null,null,"Cherry","free",SessionStatusEnum.READY);
        session.freezeOpening(decision(text)); when(service.requireSession("s1")).thenReturn(session); return session;
    }
    /** 待机选课先落状态，实际开场只播所选课首题；重复开始及旧继续课邀请均不能追加播报。 */
    @Test public void courseSelectedBeforeOpeningReplacesFrozenFreeGreeting() {
        var session = session("要继续上次课程，还是自由聊聊？");
        var recent = mock(cn.adinall.ai.domain.practice.adapter.repository.IRecentLessonRepository.class);
        var practice = new cn.adinall.ai.domain.practice.service.PracticeService(recent);
        var books = new cn.adinall.ai.domain.scenario.service.TextbookService(
                new cn.adinall.ai.infrastructure.adapter.repository.ResourceTextbookRepository(new com.fasterxml.jackson.databind.ObjectMapper()));
        var courses = new cn.adinall.ai.cases.practice.PracticeCase(service, practice, books, events);
        var lesson = books.list().get(0).lessons().get(0);
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

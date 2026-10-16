package cn.adinall.ai.cases.conversation;

import cn.adinall.ai.domain.coaching.teaching.service.TeachingService;
import cn.adinall.ai.domain.coaching.teaching.model.valobj.TeachingDecisionVO;
import cn.adinall.ai.domain.coaching.teaching.model.valobj.TeachingTaskVO;

import cn.adinall.ai.cases.teaching.TeachingPreparation;
import cn.adinall.ai.domain.companion.service.ILongTermMemoryService;
import cn.adinall.ai.domain.coaching.conversation.model.aggregate.ConversationSessionAggregate;
import cn.adinall.ai.domain.coaching.conversation.model.valobj.*;
import cn.adinall.ai.domain.coaching.teaching.model.valobj.TeachingActionEnum;
import cn.adinall.ai.domain.coaching.teaching.model.valobj.PronunciationFeedbackVO;
import cn.adinall.ai.domain.coaching.teaching.service.reply.ReplyPromptBuilder;
import cn.adinall.ai.domain.learning.adapter.repository.ILearnerProgressRepository;
import cn.adinall.ai.domain.learning.model.valobj.EnglishLevelEnum;
import cn.adinall.ai.domain.learning.service.level.LearnerProgressService;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** 验证同一会话的档位热更新、轮次快照、旧跟读目标及账号隔离，不访问模型或外部数据库。 */
public class LiveEnglishLevelTest {
    /** 每个测试独立持有教学运行态，并显式共享给相关应用协作者。 */
    private final TeachingService teachingStates = new TeachingService();

    private final ILearnerProgressRepository repository = mock(ILearnerProgressRepository.class);
    private final LearnerProgressService progress = new LearnerProgressService(repository);
    private final TeachingPreparation teaching = new TeachingPreparation(null, progress, null,
            mock(ILongTermMemoryService.class), null, null,
            mock(cn.adinall.ai.domain.coaching.practice.service.PracticeService.class), mock(cn.adinall.ai.domain.content.service.TextbookService.class),
            mock(cn.adinall.ai.domain.coaching.conversation.adapter.repository.IOpeningHistoryRepository.class), CaseTestFixture.actors(), teachingStates);

    private ConversationSessionAggregate session(String subject) {
        ConversationSessionAggregate value = new ConversationSessionAggregate("s-live", "provider", "device", "avatar", "model", "voice", "free", SessionStatusEnum.READY);
        value.bindSubject(subject);
        teachingStates.state(value.getSessionId()).initialize(EnglishLevelEnum.L2);
        return value;
    }

    @Test public void nextReplyUsesLatestSelectionAndOldDecisionStaysFrozen() {
        ConversationSessionAggregate session = session("user:7");
        when(repository.level("user:7")).thenReturn(EnglishLevelEnum.L2);
        TeachingDecisionVO previous = teaching.decide(session, "我想学音标", null, false);
        when(repository.manualLevel("user:7")).thenReturn(EnglishLevelEnum.L5);
        TeachingDecisionVO next = teaching.decide(session, "请继续讲解", null, false);
        assertEquals(EnglishLevelEnum.L2, previous.level());
        assertEquals(EnglishLevelEnum.L5, next.level());
        assertTrue(ReplyPromptBuilder.buildSystemPolicy(next).contains("L5"));
        verify(repository, never()).promote(anyString(), any(), any(), anyString());
    }

    @Test public void switchingLevelKeepsReadTargetAndRetryCount() {
        ConversationSessionAggregate session = session("user:7");
        teachingStates.state(session.getSessionId()).guidedReading(true);
        TeachingTaskVO target = new TeachingTaskVO(TeachingActionEnum.READ.code(), "How are you?", "", 2);
        teachingStates.state(session.getSessionId()).commit(target);
        when(repository.manualLevel("user:7")).thenReturn(EnglishLevelEnum.L6);
        assertEquals(EnglishLevelEnum.L6, teaching.level(session));
        assertEquals(target, teachingStates.state(session.getSessionId()).pending());
        TeachingDecisionVO retry = teaching.decide(session, "How are you?", new PronunciationFeedbackVO(60.0, java.util.List.of()), true);
        assertEquals("How are you?", retry.nextTask().target());
        assertEquals(3, retry.nextTask().attempts());
        TeachingDecisionVO pass = teaching.decide(session, "How are you?", new PronunciationFeedbackVO(95.0, java.util.List.of()), true);
        assertEquals(EnglishLevelEnum.L6, pass.level());
        assertEquals("", pass.nextTask().target());
    }

    @Test public void clearingManualRestoresAutomaticWithoutReadingOtherAccount() {
        when(repository.manualLevel("user:7")).thenReturn(EnglishLevelEnum.L6, null);
        when(repository.level("user:7")).thenReturn(EnglishLevelEnum.L3);
        ConversationSessionAggregate session = session("user:7");
        assertEquals(EnglishLevelEnum.L6, teaching.level(session));
        assertEquals(EnglishLevelEnum.L3, teaching.level(session));
        verify(repository, never()).manualLevel("user:8");
        verify(repository, never()).setManualLevel(anyString(), any());
    }

    @Test public void storageFailureKeepsLastConfirmedDifficulty() {
        when(repository.level("user:7")).thenThrow(new IllegalStateException("offline"));
        assertEquals(EnglishLevelEnum.L4, progress.forReply("user:7", EnglishLevelEnum.L4));
    }
}

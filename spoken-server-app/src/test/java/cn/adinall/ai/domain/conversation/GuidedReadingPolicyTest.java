package cn.adinall.ai.domain.conversation;

import cn.adinall.ai.domain.conversation.model.aggregate.TeachingStateAggregate;
import cn.adinall.ai.domain.conversation.model.valobj.*;
import cn.adinall.ai.domain.conversation.service.reply.*;
import cn.adinall.ai.domain.learner.model.valobj.EnglishLevelEnum;
import org.junit.Test;
import reactor.core.publisher.Flux;
import java.util.List;
import static org.junit.Assert.*;

/** 情境跟读规则回归：只对实际英文目标评测，三次纠音硬上限，缺证据与新问题不误判。 */
public class GuidedReadingPolicyTest {
    private TeachingStateAggregate started() {
        var state = new TeachingStateAggregate();
        state.initialize(EnglishLevelEnum.L2);
        state.guidedReading(true);
        state.readingOpening("intro");
        assertTrue(state.completeReadingOpening("intro", GuidedReadingPolicy.sentence(EnglishLevelEnum.L2, 0).demonstration()));
        return state;
    }

    @Test public void narrationIsNotTheAssessmentTargetAndUnplayedIntroductionDoesNotBecomeTask() {
        var state = new TeachingStateAggregate(); state.initialize(EnglishLevelEnum.L2); state.guidedReading(true);
        state.readingOpening("intro");
        assertEquals("", state.readingMode().target());
        assertFalse(state.completeReadingOpening("old", "How are you?"));
        String line = GuidedReadingPolicy.sentence(EnglishLevelEnum.L2, 0).demonstration();
        assertTrue(state.completeReadingOpening("intro", line));
        assertEquals("How are you?", state.readingMode().target());
        assertFalse(state.readingMode().target().contains("意思"));
    }

    @Test public void interruptedIntroductionCannotLaterCommit() {
        var state = started(); state.interrupt();
        assertFalse(state.completeReadingOpening("intro", GuidedReadingPolicy.sentence(EnglishLevelEnum.L2, 0).demonstration()));
        assertEquals("", state.readingMode().target());
    }

    @Test public void exactlyThreeCorrectionsThenNextSentenceEvenWhenStillBelowPass() {
        var state = started();
        var low = new PronunciationFeedbackVO(40.0, List.of(new PronunciationFeedbackVO.IssueVO("you", "juː", "u", .95)));
        for (int attempt = 1; attempt <= 3; attempt++) {
            var d = GuidedReadingPolicy.decide(EnglishLevelEnum.L2, state.pending(), "How are you?", low, true, state.readingIndex());
            assertEquals(TeachingOutcomeEnum.READ_RETRY, d.outcome());
            assertEquals(attempt, d.nextTask().attempts());
            String spoken = GuidedReadingPolicy.guard(Flux.just("错误的模型回复，只读旧目标"), d, 0).blockFirst();
            state.commitSpoken(d, spoken);
            // 流式和媒体完成重复提交不能把一次纠音累计两次。
            state.commitSpoken(d, spoken);
            assertEquals(attempt, state.pending().attempts());
            assertEquals("How are you?", state.pending().target());
        }
        var d = GuidedReadingPolicy.decide(EnglishLevelEnum.L2, state.pending(), "How are you?", low, true, 0);
        assertEquals(TeachingOutcomeEnum.READ_LIMIT_REACHED, d.outcome());
        String spoken = GuidedReadingPolicy.guard(Flux.just("再读：“How are you?”，请跟读。"), d, 0).blockFirst();
        assertFalse(spoken.contains("How are you?"));
        assertTrue(spoken.contains("I'm doing well, thanks."));
        state.commitSpoken(d, spoken);
        assertEquals(1, state.readingIndex());
        assertEquals(0, state.pending().attempts());
    }

    @Test public void validPassImmediatelyAdvancesWithContext() {
        var state = started();
        var d = GuidedReadingPolicy.decide(EnglishLevelEnum.L2, state.pending(), "How are you?", new PronunciationFeedbackVO(92.0, List.of()), true, 0);
        assertEquals(TeachingOutcomeEnum.READ_PASS, d.outcome());
        String spoken = GuidedReadingPolicy.guard(Flux.empty(), d, 0).blockFirst();
        assertTrue(spoken.contains("朋友也问起你的近况"));
        state.commitSpoken(d, spoken);
        assertEquals("I'm doing well, thanks.", state.pending().target());
    }

    @Test public void missingAssessmentDoesNotInventFailureOrConsumeCorrectionQuota() {
        var state = started();
        for (Double score : new Double[]{null, Double.NaN, -1.0, 101.0}) {
            var d = GuidedReadingPolicy.decide(EnglishLevelEnum.L2, state.pending(), "How are you?", new PronunciationFeedbackVO(score, List.of()), true, 0);
            assertEquals(TeachingOutcomeEnum.NO_EVIDENCE, d.outcome());
            assertEquals(0, d.nextTask().attempts());
            assertEquals("How are you?", d.nextTask().target());
        }
    }

    @Test public void lowScoreWithoutSpecificEvidenceOnlyDemonstratesAndEventuallySkips() {
        var d = GuidedReadingPolicy.decide(EnglishLevelEnum.L2, started().pending(), "How are you?", new PronunciationFeedbackVO(60.0, List.of()), true, 0);
        assertEquals(TeachingOutcomeEnum.READ_RETRY, d.outcome());
        assertEquals("", d.correctionHint());
        String spoken = GuidedReadingPolicy.guard(Flux.just("你的 you 发音错了，请读：“How are you?”。请跟读。"), d, 0).blockFirst();
        assertFalse(spoken.contains("发音错了"));
        assertTrue(spoken.contains("连贯"));
    }

    @Test public void realPhoneEvidenceCanGuideWithoutWordConfidence() {
        var phone = new PronunciationFeedbackVO.PhoneVO("j", "", .93);
        var issue = new PronunciationFeedbackVO.IssueVO("you", "juː", "u", null, List.of(phone));
        var d = GuidedReadingPolicy.decide(EnglishLevelEnum.L2, started().pending(), "How are you?", new PronunciationFeedbackVO(90.0, List.of(issue)), true, 0);
        assertEquals(TeachingOutcomeEnum.READ_RETRY, d.outcome());
        assertTrue(d.correctionHint().contains("/j/"));
        assertFalse(d.correctionHint().contains("/null/"));
    }

    @Test public void unknownOrUnrelatedErrorsCannotProduceSpecificCorrection() {
        var feedback = new PronunciationFeedbackVO(50.0, List.of(new PronunciationFeedbackVO.IssueVO("books", "b", "p", .99),
                new PronunciationFeedbackVO.IssueVO("you", "j", "u", .2)));
        assertNull(PronunciationEvidencePolicy.credibleIssue("How are you?", feedback));
    }

    @Test public void englishQuestionsAndPauseTakePriorityOverReading() {
        for (String text : List.of("我想学习音标，你告诉我音标有哪些？", "What does this sentence mean?", "Please stop", "I want to learn phonetics")) {
            assertFalse(text, GuidedReadingPolicy.isAttempt("How are you?", text));
            var d = GuidedReadingPolicy.decide(EnglishLevelEnum.L2, started().pending(), text, null, true, 0);
            assertFalse(d.practiceInput());
            assertFalse(d.nextTask().reading());
        }
        assertTrue(GuidedReadingPolicy.isAttempt("How are you?", "How are you?"));
        assertTrue(GuidedReadingPolicy.isAttempt("I'm doing well, thanks.", "doing well"));
    }

    @Test public void sixLevelsUseDifferentDifficultyAndSequentialMeanings() {
        assertEquals("Hello!", GuidedReadingPolicy.sentence(EnglishLevelEnum.L1, 0).english());
        assertEquals("How are you?", GuidedReadingPolicy.sentence(EnglishLevelEnum.L2, 0).english());
        assertTrue(GuidedReadingPolicy.sentence(EnglishLevelEnum.L6, 0).english().split(" ").length > 10);
        for (var level : EnglishLevelEnum.values()) for (var s : GuidedReadingPolicy.sentences(level)) {
            var task = SpokenTaskResolver.resolve(TeachingTaskVO.response(), s.demonstration());
            assertTrue(task.reading());
            assertEquals(s.english(), task.target());
        }
    }
}

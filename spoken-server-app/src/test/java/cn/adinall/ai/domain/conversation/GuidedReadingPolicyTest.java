package cn.adinall.ai.domain.conversation;

import cn.adinall.ai.domain.conversation.model.aggregate.TeachingStateAggregate;
import cn.adinall.ai.domain.conversation.model.valobj.*;
import cn.adinall.ai.domain.conversation.service.reply.*;
import cn.adinall.ai.domain.learner.model.valobj.EnglishLevelEnum;
import org.junit.Test;
import reactor.core.publisher.Flux;
import java.util.List;
import static org.junit.Assert.*;

/** 模型自主生成跟读：通过/跳过请求新句、纠音锁定旧句、近期去重和媒体提交边界。 */
public class GuidedReadingPolicyTest {
    private TeachingDecisionVO fresh() {
        return new TeachingDecisionVO(EnglishLevelEnum.L2, new TeachingTaskVO("READ", "", "", 0),
                TeachingOutcomeEnum.RESPONDED, true, "", false, true);
    }
    private String demo(String english) { return "想和朋友接着聊，可以说：“" + english + "”，意思是表达最近的情况。你来试着读一读吧。"; }
    private TeachingStateAggregate started() {
        var state = new TeachingStateAggregate(); state.initialize(EnglishLevelEnum.L2); state.guidedReading(true);
        state.commitSpoken(fresh(), demo("How are you?")); return state;
    }
    private TeachingDecisionVO decide(TeachingStateAggregate state, PronunciationFeedbackVO feedback) {
        return GuidedReadingPolicy.decide(EnglishLevelEnum.L2, state.pending(), "How are you?", feedback, true);
    }
    @Test public void targetComesFromActualModelSpeechInsteadOfFixedPool() {
        var state = new TeachingStateAggregate(); state.initialize(EnglishLevelEnum.L2); state.guidedReading(true);
        assertEquals("", state.readingMode().target());
        String generated = demo("Could we sit by the window?");
        assertEquals(generated, GuidedReadingPolicy.guard(Flux.just(generated), fresh(), List.of()).blockFirst());
        state.commitSpoken(fresh(), generated);
        assertEquals("Could we sit by the window?", state.readingMode().target());
        assertEquals(List.of("Could we sit by the window?"), state.recentReadingTargets());
    }
    @Test public void exactlyThreeCorrectionsThenRequestModelToGenerateAnotherSentence() {
        var state = started();
        var low = new PronunciationFeedbackVO(40.0, List.of(new PronunciationFeedbackVO.IssueVO("you", "juː", "u", .95)));
        for (int attempt = 1; attempt <= 3; attempt++) {
            var d = decide(state, low);
            assertEquals(TeachingOutcomeEnum.READ_RETRY, d.outcome()); assertEquals(attempt, d.nextTask().attempts());
            String spoken = GuidedReadingPolicy.guard(Flux.just(demo("How are you?")), d, state.recentReadingTargets()).blockFirst();
            state.commitSpoken(d, spoken); state.commitSpoken(d, spoken);
            assertEquals(attempt, state.pending().attempts());
        }
        var d = decide(state, low);
        assertEquals(TeachingOutcomeEnum.READ_LIMIT_REACHED, d.outcome()); assertEquals("", d.nextTask().target());
        rejected(demo("HOW ARE YOU!"), d, state.recentReadingTargets());
        String next = demo("Have you tried the tea here?");
        state.commitSpoken(d, GuidedReadingPolicy.guard(Flux.just(next), d, state.recentReadingTargets()).blockFirst());
        assertEquals("Have you tried the tea here?", state.pending().target()); assertEquals(0, state.pending().attempts());
    }
    @Test public void passDoesNotPreselectNextSentence() {
        var state = started(); var d = decide(state, new PronunciationFeedbackVO(92.0, List.of()));
        assertEquals(TeachingOutcomeEnum.READ_PASS, d.outcome()); assertEquals("", d.nextTask().target());
        String generated = demo("I have been learning to bake.");
        state.commitSpoken(d, GuidedReadingPolicy.guard(Flux.just(generated), d, state.recentReadingTargets()).blockFirst());
        assertEquals("I have been learning to bake.", state.pending().target());
    }
    @Test public void missingEvidenceKeepsActualTargetAndDoesNotConsumeQuota() {
        var state = started();
        for (Double score : new Double[]{null, Double.NaN, -1.0, 101.0}) {
            var d = decide(state, new PronunciationFeedbackVO(score, List.of()));
            assertEquals(TeachingOutcomeEnum.NO_EVIDENCE, d.outcome()); assertEquals(0, d.nextTask().attempts());
            assertEquals("How are you?", d.nextTask().target());
        }
    }
    @Test public void retriesCannotSilentlyReplaceTheSentence() {
        var state = started(); var d = decide(state, new PronunciationFeedbackVO(60.0, List.of()));
        rejected(demo("What is your name?"), d, state.recentReadingTargets());
        String reply = GuidedReadingPolicy.guard(Flux.just(demo("How are you?")), d, state.recentReadingTargets()).blockFirst();
        assertTrue(reply.contains("连贯")); assertTrue(reply.contains("How are you?"));
    }
    @Test public void newTargetsCannotRepeatRecentSentencesEvenWithOtherPunctuation() {
        rejected(demo("HOW ARE YOU!"), fresh(), List.of("How are you?"));
        rejected("仅有中文，没有目标。", fresh(), List.of());
        rejected("“I like tea.” Please repeat.", fresh(), List.of());
        rejected(demo("I like tea.") + demo("I like coffee."), fresh(), List.of());
    }
    @Test public void recentTargetsSurviveModeToggleAndRemainBoundedWithoutRecommittingDuplicates() {
        var state = started(); state.commitSpoken(fresh(), demo("How are you?"));
        assertEquals(1, state.recentReadingTargets().size());
        state.guidedReading(false); state.guidedReading(true);
        assertEquals(List.of("How are you?"), state.recentReadingTargets());
        for (String word : List.of("tea", "coffee", "cake", "rice", "bread", "fruit", "milk", "juice", "water"))
            state.commitSpoken(fresh(), demo("I enjoy " + word + "."));
        assertEquals(8, state.recentReadingTargets().size());
        assertEquals("I enjoy water.", state.pending().target());
    }
    @Test public void realPhoneEvidenceCanGuideWithoutWordConfidence() {
        var issue = new PronunciationFeedbackVO.IssueVO("you", "juː", "u", null,
                List.of(new PronunciationFeedbackVO.PhoneVO("j", "", .93)));
        var d = decide(started(), new PronunciationFeedbackVO(90.0, List.of(issue)));
        assertEquals(TeachingOutcomeEnum.READ_RETRY, d.outcome()); assertTrue(d.correctionHint().contains("/j/"));
        assertNull(PronunciationEvidencePolicy.credibleIssue("How are you?", new PronunciationFeedbackVO(40.0,
                List.of(new PronunciationFeedbackVO.IssueVO("books", "b", "p", .99)))));
    }
    @Test public void knowledgeQuestionsStillTakePriority() {
        for (String text : List.of("我想学习音标，你告诉我音标有哪些？", "What does this sentence mean?", "Please stop", "I want to learn phonetics")) {
            var d = GuidedReadingPolicy.decide(EnglishLevelEnum.L2, started().pending(), text, null, true);
            assertFalse(d.practiceInput()); assertFalse(d.nextTask().reading());
        }
        assertTrue(GuidedReadingPolicy.isAttempt("How are you?", "How you"));
    }
    private void rejected(String generated, TeachingDecisionVO d, List<String> recent) {
        try { GuidedReadingPolicy.guard(Flux.just(generated), d, recent).blockFirst(); fail("无效结果不应进入播报"); }
        catch (IllegalStateException expected) { assertTrue(expected.getMessage().startsWith("跟读生成结果")); }
    }
}

package cn.adinall.ai.domain.coaching.teaching;

import cn.adinall.ai.domain.coaching.teaching.model.valobj.PronunciationFeedbackVO;
import cn.adinall.ai.domain.coaching.teaching.model.valobj.PronunciationFeedbackVO.IssueVO;
import cn.adinall.ai.domain.coaching.teaching.model.valobj.TeachingDecisionVO;
import cn.adinall.ai.domain.coaching.teaching.service.reply.ReadingGenerationException;

import cn.adinall.ai.domain.coaching.teaching.model.aggregate.TeachingStateAggregate;
import cn.adinall.ai.domain.coaching.conversation.model.valobj.*;
import cn.adinall.ai.domain.coaching.teaching.model.valobj.TeachingOutcomeEnum;
import cn.adinall.ai.domain.coaching.teaching.model.valobj.TeachingTaskVO;
import cn.adinall.ai.domain.coaching.teaching.service.reply.*;
import cn.adinall.ai.domain.learning.model.valobj.EnglishLevelEnum;
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
        TeachingStateAggregate state = new TeachingStateAggregate(); state.initialize(EnglishLevelEnum.L2); state.guidedReading(true);
        state.commitSpoken(fresh(), demo("How are you?")); return state;
    }
    private TeachingDecisionVO decide(TeachingStateAggregate state, PronunciationFeedbackVO feedback) {
        return GuidedReadingPolicy.decide(EnglishLevelEnum.L2, state.pending(), "How are you?", feedback, true);
    }
    @Test public void targetComesFromActualModelSpeechInsteadOfFixedPool() {
        TeachingStateAggregate state = new TeachingStateAggregate(); state.initialize(EnglishLevelEnum.L2); state.guidedReading(true);
        assertEquals("", state.readingMode().target());
        String generated = demo("Could we sit by the window?");
        assertEquals(generated, GuidedReadingPolicy.guard(Flux.just(generated), fresh(), List.of()).blockFirst());
        state.commitSpoken(fresh(), generated);
        assertEquals("Could we sit by the window?", state.readingMode().target());
        assertEquals(List.of("Could we sit by the window?"), state.recentReadingTargets());
    }
    @Test public void exactlyThreeCorrectionsThenRequestModelToGenerateAnotherSentence() {
        TeachingStateAggregate state = started();
        PronunciationFeedbackVO low = new PronunciationFeedbackVO(40.0, List.of(new PronunciationFeedbackVO.IssueVO("you", "juː", "u", .95)));
        for (int attempt = 1; attempt <= 3; attempt++) {
            TeachingDecisionVO d = decide(state, low);
            assertEquals(TeachingOutcomeEnum.READ_RETRY, d.outcome()); assertEquals(attempt, d.nextTask().attempts());
            String spoken = GuidedReadingPolicy.guard(Flux.just(demo("How are you?")), d, state.recentReadingTargets()).blockFirst();
            state.commitSpoken(d, spoken); state.commitSpoken(d, spoken);
            assertEquals(attempt, state.pending().attempts());
        }
        TeachingDecisionVO d = decide(state, low);
        assertEquals(TeachingOutcomeEnum.READ_LIMIT_REACHED, d.outcome()); assertEquals("", d.nextTask().target());
        rejected(demo("HOW ARE YOU!"), d, state.recentReadingTargets());
        String next = demo("Have you tried the tea here?");
        state.commitSpoken(d, GuidedReadingPolicy.guard(Flux.just(next), d, state.recentReadingTargets()).blockFirst());
        assertEquals("Have you tried the tea here?", state.pending().target()); assertEquals(0, state.pending().attempts());
    }
    @Test public void passDoesNotPreselectNextSentence() {
        TeachingStateAggregate state = started(); TeachingDecisionVO d = decide(state, new PronunciationFeedbackVO(92.0, List.of()));
        assertEquals(TeachingOutcomeEnum.READ_PASS, d.outcome()); assertEquals("", d.nextTask().target());
        String generated = demo("I have been learning to bake.");
        state.commitSpoken(d, GuidedReadingPolicy.guard(Flux.just(generated), d, state.recentReadingTargets()).blockFirst());
        assertEquals("I have been learning to bake.", state.pending().target());
    }
    @Test public void missingEvidenceKeepsActualTargetAndDoesNotConsumeQuota() {
        TeachingStateAggregate state = started();
        for (Double score : new Double[]{null, Double.NaN, -1.0, 101.0}) {
            TeachingDecisionVO d = decide(state, new PronunciationFeedbackVO(score, List.of()));
            assertEquals(TeachingOutcomeEnum.NO_EVIDENCE, d.outcome()); assertEquals(0, d.nextTask().attempts());
            assertEquals("How are you?", d.nextTask().target());
        }
    }
    @Test public void retriesCannotSilentlyReplaceTheSentence() {
        TeachingStateAggregate state = started(); TeachingDecisionVO d = decide(state, new PronunciationFeedbackVO(60.0, List.of()));
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
        TeachingStateAggregate state = started(); state.commitSpoken(fresh(), demo("How are you?"));
        assertEquals(1, state.recentReadingTargets().size());
        state.guidedReading(false); state.guidedReading(true);
        assertEquals(List.of("How are you?"), state.recentReadingTargets());
        for (String word : List.of("tea", "coffee", "cake", "rice", "bread", "fruit", "milk", "juice", "water"))
            state.commitSpoken(fresh(), demo("I enjoy " + word + "."));
        assertEquals(8, state.recentReadingTargets().size());
        assertEquals("I enjoy water.", state.pending().target());
    }
    @Test public void realPhoneEvidenceCanGuideWithoutWordConfidence() {
        IssueVO issue = new PronunciationFeedbackVO.IssueVO("you", "juː", "u", null,
                List.of(new PronunciationFeedbackVO.PhoneVO("j", "", .93)));
        TeachingDecisionVO d = decide(started(), new PronunciationFeedbackVO(90.0, List.of(issue)));
        assertEquals(TeachingOutcomeEnum.READ_RETRY, d.outcome()); assertTrue(d.correctionHint().contains("/j/"));
        assertNull(PronunciationEvidencePolicy.credibleIssue("How are you?", new PronunciationFeedbackVO(40.0,
                List.of(new PronunciationFeedbackVO.IssueVO("books", "b", "p", .99)))));
    }
    @Test public void knowledgeQuestionsStillTakePriority() {
        for (String text : List.of("我想学习音标，你告诉我音标有哪些？", "What does this sentence mean?", "Please stop", "I want to learn phonetics")) {
            TeachingDecisionVO d = GuidedReadingPolicy.decide(EnglishLevelEnum.L2, started().pending(), text, null, true);
            assertFalse(d.practiceInput()); assertFalse(d.nextTask().reading());
        }
        assertTrue(GuidedReadingPolicy.isAttempt("How are you?", "How you"));
    }
    /** 真实失败日志：请朗读与目标问号均属于示范，不能当作普通聊天问题。 */
    @Test public void acceptsActualLoggedReadingAndCommitsTheSameTarget() {
        String logged = "We are in a free English chat. Let's practice a simple everyday question.\n\n\"Where do you live?\"\n中文含义：你住在哪里？\n\n请朗读：Where do you live?";
        String checked = GuidedReadingPolicy.guard(Flux.just(logged), fresh(), List.of()).blockFirst();
        assertEquals(logged, checked);
        TeachingStateAggregate state = new TeachingStateAggregate(); state.guidedReading(true);
        state.commitSpoken(fresh(), checked);
        assertEquals("Where do you live?", state.pending().target());
        assertTrue(SpokenTaskResolver.resolve(TeachingTaskVO.response(), logged).reading());
    }

    /** 重复引用合并；允许常规数字标点，任务明确时缺少邀请可以补齐而非重跑模型。 */
    @Test public void toleratesRepeatedTargetAndAddsMissingInvitation() {
        for (String quote : List.of("\"I finish at 7:30; then I walk.\"", "‘I finish at 7:30; then I walk.’")) {
            String spoken = "描述下班后的安排，可以说：" + quote + "，中文是我七点半结束，然后散步。再次示范：" + quote;
            String checked = GuidedReadingPolicy.guard(Flux.just(spoken), fresh(), List.of()).blockFirst();
            assertTrue(checked.endsWith("你来试着读一读吧。"));
            TeachingStateAggregate state = new TeachingStateAggregate(); state.guidedReading(true); state.commitSpoken(fresh(), checked);
            assertEquals("I finish at 7:30; then I walk.", state.pending().target());
        }
    }

    /** 不能为了降低失败率容忍多题、改题或明确拒绝；业务原因分别可用于模型修正。 */
    @Test public void reportsSpecificTypedReasonsInsteadOfOneGenericFailure() {
        assertReason("只有中文讲解，没有英文题面。", fresh(), List.of(), ReadingGenerationErrorEnum.MISSING_TARGET);
        assertReason(demo("I like tea.") + demo("I like coffee."), fresh(), List.of(), ReadingGenerationErrorEnum.MULTIPLE_TARGETS);
        assertReason(demo("How are you?"), fresh(), List.of("How are you?"), ReadingGenerationErrorEnum.RECENT_DUPLICATE);
        TeachingDecisionVO retry = new TeachingDecisionVO(EnglishLevelEnum.L2, new TeachingTaskVO("READ", "How are you?", "", 1), TeachingOutcomeEnum.NO_EVIDENCE,true,"",false,true);
        assertReason(demo("I like tea."), retry, List.of(), ReadingGenerationErrorEnum.TARGET_CHANGED);
        assertReason("不用跟读，只解释：\"I like tea.\"，意思是我喜欢茶。", fresh(), List.of(), ReadingGenerationErrorEnum.READING_DECLINED);
        assertReason("\"I like tea.\"", fresh(), List.of(), ReadingGenerationErrorEnum.MISSING_EXPLANATION);
        assertReason("At a cafe say \"I like tea.\". Please read.", fresh(), List.of(), ReadingGenerationErrorEnum.MISSING_CHINESE);
    }
    private void assertReason(String text, TeachingDecisionVO decision, List<String> recent, ReadingGenerationErrorEnum reason) {
        ReadingGenerationException failure = assertThrows(ReadingGenerationException.class, () -> GuidedReadingPolicy.guard(Flux.just(text),decision,recent).blockFirst());
        assertEquals(reason, failure.reason());
    }

    private void rejected(String generated, TeachingDecisionVO d, List<String> recent) {
        try { GuidedReadingPolicy.guard(Flux.just(generated), d, recent).blockFirst(); fail("无效结果不应进入播报"); }
        catch (IllegalStateException expected) { assertTrue(expected.getMessage().startsWith("跟读生成结果")); }
    }
}

package cn.adinall.ai.domain.conversation;

import cn.adinall.ai.domain.conversation.model.aggregate.TeachingStateAggregate;
import cn.adinall.ai.domain.conversation.model.valobj.*;
import cn.adinall.ai.domain.conversation.service.reply.*;
import cn.adinall.ai.domain.learner.model.valobj.EnglishLevelEnum;
import org.junit.Test;
import reactor.core.publisher.*;
import java.util.*;
import static org.junit.Assert.*;

/**
 * 功能：验证任务互斥、拒绝不扣能力、可靠纠音和逐句流式护栏。
 * <p>体系架构 / 运行过程：</p>
 * <pre>task/input/assessment -> policy -> action/script assertions</pre>
 * <p>案例：同句连续失败 -> 退出原目标，不无限重读。</p>
 * <p>设计要点：</p>
 * <ul><li>测用户可观察行为。</li><li>不访问模型或数据库。</li></ul>
 */
public class TeachingPolicyTest {
    private TeachingTaskVO read(int attempts) { return new TeachingTaskVO("READ","I cooked dinner.","",attempts); }
    @Test public void refusalExitsReadingWithoutDowngradeOrAnotherReading() {
        var d=TeachingPolicy.decide(EnglishLevelEnum.L2,read(1),"不想读",null,true);
        assertEquals("DECLINED",d.outcome()); assertEquals(EnglishLevelEnum.L2,d.level());
        assertFalse(d.nextTask().reading()); assertTrue(d.script().contains("也没关系")); assertTrue(d.practiceInput());
    }
    @Test public void notKnowingAnswerUsesGentleSingleQuestion() {
        var d=TeachingPolicy.decide(EnglishLevelEnum.L5,TeachingTaskVO.question("Where did you go?"),"听不懂",null,true);
        assertEquals("HELP",d.outcome()); assertTrue(d.chineseSupport()); assertEquals(EnglishLevelEnum.L5,d.level());
        assertFalse(d.nextTask().reading()); assertFalse(d.script().contains("不想"));
    }
    @Test public void expressingDifficultyGivesOneReadingTargetWithoutQuestion() {
        var d=TeachingPolicy.decide(EnglishLevelEnum.L2,TeachingTaskVO.question(""),"我不会说",null,true);
        assertTrue(d.nextTask().reading()); assertFalse(d.script().contains("？")); assertFalse(d.script().contains("?"));
    }
    @Test public void expressionHelpKeepsPreviousTopic() {
        var d=TeachingPolicy.decide(EnglishLevelEnum.L2,TeachingTaskVO.question("你喜欢美式还是拿铁？"),"我不会说",null,false);
        assertEquals("I like coffee.",d.nextTask().target());
    }
    @Test public void highFreeConversationScoreDoesNotTriggerCorrectionOrPromotion() {
        var f=new PronunciationFeedbackVO(99.,List.of(new PronunciationFeedbackVO.IssueVO("hello","h","x",1.)));
        var d=TeachingPolicy.decide(EnglishLevelEnum.L2,TeachingTaskVO.question("How are you?"),"I am fine",f,true);
        assertEquals(EnglishLevelEnum.L2,d.level()); assertFalse(d.reliableAssessment()); assertFalse(d.nextTask().reading());
    }
    @Test public void onlyReliableSpecificIssueTriggersCorrection() {
        var f=new PronunciationFeedbackVO(55.,List.of(new PronunciationFeedbackVO.IssueVO("cooked","kʊkt","kʊk",.95)));
        var d=TeachingPolicy.decide(EnglishLevelEnum.L2,read(0),"I cooked dinner",f,true);
        assertEquals("CORRECT",d.nextTask().action()); assertTrue(d.script().contains("t 音")); assertFalse(d.script().contains("？"));
    }
    @Test public void missingConfidenceDoesNotInventError() {
        var f=new PronunciationFeedbackVO(40.,List.of(new PronunciationFeedbackVO.IssueVO("cooked","t","x")));
        var d=TeachingPolicy.decide(EnglishLevelEnum.L2,read(0),"I cooked dinner",f,true);
        assertFalse(d.reliableAssessment()); assertFalse(d.script().contains("末尾"));
    }
    @Test public void twoRetriesDoNotKeepSameTargetAlive() {
        var f=new PronunciationFeedbackVO(30.,List.of(new PronunciationFeedbackVO.IssueVO("cooked","t","",.99)));
        var d=TeachingPolicy.decide(EnglishLevelEnum.L2,read(2),"I cooked dinner",f,true);
        assertFalse(d.nextTask().reading()); assertFalse(d.script().contains("I cooked dinner"));
    }
    @Test public void noAudioIsNotRefusalOrZeroAbility() {
        var d=TeachingPolicy.decide(EnglishLevelEnum.L4,TeachingTaskVO.question(""),"",null,true);
        assertEquals("NO_AUDIO",d.outcome()); assertEquals(EnglishLevelEnum.L4,d.level());
    }
    @Test public void levelFreezesForWholeSessionAndInterruptDropsPendingTask() {
        var state=new TeachingStateAggregate();
        state.initialize(EnglishLevelEnum.L2); state.initialize(EnglishLevelEnum.L6);
        assertEquals(EnglishLevelEnum.L2,state.level()); state.commit(read(0)); state.interrupt(); assertFalse(state.pending().reading());
    }
    @Test public void guardRejectsMixedTasksAndExtraQuestions() {
        var d=TeachingPolicy.decide(EnglishLevelEnum.L2,TeachingTaskVO.question(""),"pizza",null,false);
        var result=TeachingReplyGuard.apply(Flux.just("跟我读这一句。你喜欢什么菜？再问一个问题？"),d).collectList().block();
        assertEquals(List.of("你喜欢什么菜？"),result);
    }
    @Test public void guardEmitsFirstSentenceBeforeModelCompletes() {
        var source=Sinks.many().unicast().<String>onBackpressureBuffer();
        List<String> result=new ArrayList<>();
        var d=TeachingPolicy.decide(EnglishLevelEnum.L2,TeachingTaskVO.question(""),"pizza",null,false);
        var subscription=TeachingReplyGuard.apply(source.asFlux(),d).subscribe(result::add);
        source.tryEmitNext("That sounds tasty."); assertEquals(List.of("That sounds tasty. "),result);
        subscription.dispose(); assertEquals(1,result.size());
    }
}

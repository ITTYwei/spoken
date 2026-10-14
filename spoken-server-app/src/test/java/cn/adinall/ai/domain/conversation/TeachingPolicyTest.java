package cn.adinall.ai.domain.conversation;

import cn.adinall.ai.domain.conversation.model.aggregate.TeachingStateAggregate;
import cn.adinall.ai.domain.conversation.model.valobj.*;
import cn.adinall.ai.domain.conversation.service.reply.*;
import cn.adinall.ai.domain.learner.model.valobj.EnglishLevelEnum;
import org.junit.Test;
import reactor.core.publisher.Flux;
import java.util.*;
import static org.junit.Assert.*;

/** 教学参考策略回归：普通需求交给模型，跟读证据、练习隔离和实际任务提交仍受保护。 */
public class TeachingPolicyTest {
    private TeachingTaskVO read(int attempts) { return new TeachingTaskVO("READ", "I cooked dinner.", "", attempts); }
    private PronunciationFeedbackVO feedback(double score) {
        return new PronunciationFeedbackVO(score,List.of(new PronunciationFeedbackVO.IssueVO("cooked","kʊkt","kʊk",.95)));
    }
    /** 纯中文、不会说和低等级都不能被策略强行安排跟读，旧抽样值也不影响。 */
    @Test public void ordinaryRequestsNeverForceReadingAtAnyLevelOrDraw() {
        for (var level:EnglishLevelEnum.values()) for(double draw:List.of(0.,.49,.79,.99))
            for(String text:List.of("我想学习音标，音标有哪些？","我不会说","听不懂","你好","I like reading books")) {
                var d=TeachingPolicy.decide(level,null,text,null,true,draw);
                assertEquals(text,TeachingActionEnum.RESPOND.code(),d.nextTask().action());
                assertFalse(d.practiceInput()); assertEquals("",d.correctionHint());
            }
    }
    @Test public void refusalAndNewRequestsAreNotReadingEvidence() {
        for(String text:List.of("不想读","我想学习音标","I cooked dinner 是什么意思？","How do I say dinner in English?")) {
            var d=TeachingPolicy.decide(EnglishLevelEnum.L2,read(0),text,feedback(30),true);
            assertFalse(text,d.practiceInput());assertFalse(d.reliableAssessment());assertFalse(d.nextTask().reading());
        }
    }
    @Test public void reliableWordIssueIsNotHiddenByHighScore() {
        var d=TeachingPolicy.decide(EnglishLevelEnum.L2,read(0),"I cooked dinner",feedback(92),true);
        assertEquals(TeachingOutcomeEnum.READ_RETRY,d.outcome());assertTrue(d.practiceInput());assertTrue(d.reliableAssessment());
        assertEquals(TeachingActionEnum.CORRECT.code(),d.nextTask().action());assertTrue(d.correctionHint().contains("/t/"));
        assertFalse(TeachingPolicy.decide(EnglishLevelEnum.L2,read(2),"I cooked dinner",feedback(30),true).nextTask().reading());
    }
    @Test public void freeSpeechScoreAndTypedTextNeverCausePronunciationCorrection() {
        assertFalse(TeachingPolicy.decide(EnglishLevelEnum.L2,null,"I cooked dinner",feedback(30),true).reliableAssessment());
        assertFalse(TeachingPolicy.decide(EnglishLevelEnum.L2,read(0),"I cooked dinner",feedback(30),false).practiceInput());
    }
    @Test public void missingConfidenceDoesNotInventError() {
        var f=new PronunciationFeedbackVO(40.,List.of(new PronunciationFeedbackVO.IssueVO("cooked","t","x")));
        assertEquals("",TeachingPolicy.decide(EnglishLevelEnum.L2,read(0),"I cooked dinner",f,true).correctionHint());
    }
    @Test public void noAudioPreservesLevelWithoutReadingEvidence() {
        var d=TeachingPolicy.decide(EnglishLevelEnum.L4,read(0),"",null,true);
        assertEquals(TeachingOutcomeEnum.NO_AUDIO,d.outcome());assertEquals(EnglishLevelEnum.L4,d.level());assertFalse(d.practiceInput());
    }
    /** 问句原样跟读仍不让模型回答素材问题或保存其中的个人事实。 */
    @Test public void questionShapedTargetRemainsExercise() {
        var task=new TeachingTaskVO("READ","How do I say hello?","",0);
        var d=TeachingPolicy.decide(EnglishLevelEnum.L2,task,"How do I say hello",new PronunciationFeedbackVO(95.,List.of()),true);
        assertEquals(TeachingOutcomeEnum.READ_PASS,d.outcome());assertTrue(d.practiceInput());
        assertTrue(ReplyPromptBuilder.buildSystemPolicy(d).contains("不得回答它"));
        assertFalse(TeachingPolicy.decide(EnglishLevelEnum.L2,task,"How do I say hello 这句什么意思",null,true).practiceInput());
    }
    /** 提示词明确策略可调整，而且允许知识解答和国际音标，不再宣告唯一动作。 */
    @Test public void policyIsAdvisoryAndUserDataNeverBecomesSystemInstruction() {
        var d=TeachingPolicy.decide(EnglishLevelEnum.L2,null,"我想学音标",null,false);
        var system=ReplyPromptBuilder.buildSystemPolicy(d);
        assertTrue(system.contains("教学建议仅供参考"));assertTrue(system.contains("允许输出国际音标"));
        assertFalse(system.contains("唯一教学动作"));assertFalse(system.contains("persona-only-marker"));
        assertTrue(ReplyPromptBuilder.buildManaged("current-user-marker",d,"persona-only-marker",null).contains("current-user-marker"));
    }
    /** 护栏不得按建议删解释或例句：音标、第五句、问句后的解释和跟读邀请均原样保留。 */
    @Test public void streamKeepsCompleteExplanationWithoutChangingTeachingForm() {
        var d=TeachingPolicy.decide(EnglishLevelEnum.L2,null,"我想学音标",null,false);
        var chunks=List.of("❌音标用于记录读音。","可以分成元音和辅音。","例如 /iː/ 和 /p/。","你想先了解哪一类？","也可以先认识符号。","请跟读这个示范。");
        assertEquals(String.join("",chunks).replace("❌",""),String.join("",TeachingReplyGuard.apply(Flux.fromIterable(chunks),d).collectList().block()));
    }
    @Test public void onlyActuallySpokenInvitationCreatesReadingTask() {
        var planned=TeachingTaskVO.response();
        assertFalse(SpokenTaskResolver.resolve(planned,"可以这样说：“I am thirsty.” 意思是我渴了。").reading());
        assertFalse(SpokenTaskResolver.resolve(planned,"You can say “I am thirsty.”").reading());
        assertFalse(SpokenTaskResolver.resolve(planned,"“I am thirsty.” 只是例句，不需要跟读。").reading());
        var actual=SpokenTaskResolver.resolve(planned,"“I am thirsty.” 意思是我渴了。请跟读一次。");
        assertTrue(actual.reading());assertEquals("I am thirsty.",actual.target());
        assertFalse(SpokenTaskResolver.resolve(planned,"“Hello.” 或 “Good morning.” 请跟读。").reading());
    }
    @Test public void stableLevelAndInterruptRemainProtected() {
        var state=new TeachingStateAggregate();state.initialize(EnglishLevelEnum.L2);state.initialize(EnglishLevelEnum.L6);
        assertEquals(EnglishLevelEnum.L2,state.level());state.commit(read(0));state.interrupt();assertFalse(state.pending().reading());
    }
}

package cn.adinall.ai.domain.conversation;

import cn.adinall.ai.domain.conversation.model.valobj.*;
import cn.adinall.ai.domain.conversation.service.reply.*;
import cn.adinall.ai.domain.learner.model.valobj.EnglishLevelEnum;
import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

/** 知识请求无需关键词分类白名单，所有真实原话交给模型，策略只标记跟读证据。 */
public class EnglishLearningQuestionTest {
    @Test public void knowledgeRequestsAreNotForcedIntoAnyTeachingForm() {
        for(var level:EnglishLevelEnum.values()) for(String input:List.of("我想学音标，音标有哪些？","教我发音基础", "apple 是什么意思？",
                "我喜欢看书用英语怎么说？","讲讲现在完成时", "How can I learn phonetics?", "What is the present perfect?")) {
            var d=TeachingPolicy.decide(level,null,input,null,true,0);
            assertEquals(input,TeachingActionEnum.RESPOND.code(),d.nextTask().action());
            assertFalse(d.nextTask().reading());assertFalse(d.practiceInput());assertEquals("",d.correctionHint());
        }
    }
    @Test public void quotedTargetPlusQuestionIsNotPronunciationEvidence() {
        var previous=new TeachingTaskVO("READ","I cooked dinner.","",0);
        for(String question:List.of("I cooked dinner 为什么用过去式？","What does I cooked dinner mean?")) {
            assertFalse(ReadingAttemptPolicy.isAttempt(previous.target(),question));
            assertFalse(TeachingPolicy.decide(EnglishLevelEnum.L2,previous,question,new PronunciationFeedbackVO(30.,List.of()),true).practiceInput());
        }
    }
}

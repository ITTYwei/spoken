package cn.adinall.ai.domain.conversation;

import cn.adinall.ai.domain.conversation.model.valobj.*;
import cn.adinall.ai.domain.conversation.service.reply.*;
import cn.adinall.ai.domain.learner.model.valobj.EnglishLevelEnum;
import org.junit.Test;
import reactor.core.publisher.Flux;
import java.util.List;
import static org.junit.Assert.*;

/**
 * 英语知识解答的业务边界回归，不请求模型或媒体。
 * 验证真实提问优先于目标词重合、低分纠音和低档位抽样；仍保留完整问句跟读。
 * 解答中的英文示例和问句不得登记成下一轮跟读/聊天任务。
 */
public class EnglishLearningQuestionTest {
    /** 各档位下明确提问都应解答；即使已有跟读目标及低分可信错词，也不触发纠音。 */
    @Test public void knowledgeQuestionsOverrideReadingAndSamplingAtEveryLevel() {
        var previous = new TeachingTaskVO("READ", "I cooked dinner.", "", 0);
        var lowScore = new PronunciationFeedbackVO(30., List.of(
                new PronunciationFeedbackVO.IssueVO("cooked", "kʊkt", "kʊk", .95)));
        for (var level : EnglishLevelEnum.values()) {
            for (String input : List.of("apple 是什么意思？", "这是什么意思？", "cooked 怎么读？",
                    "I cooked dinner 是什么意思", "I cooked dinner 这句话怎么用过去式？",
                    "我喜欢看书用英语怎么说？", "解释一下这个句子的语法", "教我现在完成时",
                    "What does hello mean?", "How do I say hello in English?",
                    "Can you explain the past tense?", "What is the present perfect?",
                    "What is 苹果 in English?", "a 和 an 有什么区别？", "What's the difference between a and an?")) {
                var d = TeachingPolicy.decide(level, previous, input, lowScore, true, 0);
                assertEquals(input, TeachingOutcomeEnum.ENGLISH_QUESTION, d.outcome());
                assertTrue(input, d.nextTask().explaining());
                assertFalse(input, d.nextTask().reading());
                assertFalse(input, d.practiceInput());
                assertFalse(input, d.reliableAssessment());
                assertEquals(input, "", d.correctionHint());
                assertEquals(level, d.level());
            }
        }
    }

    /** 英语话题分享、普通问句和不要翻译都不是知识请求；不能用单个话题词触发。 */
    @Test public void ordinaryChatIsNotKnowledgeRequest() {
        for (String input : List.of("I like reading books", "I study English", "Do you like books?",
                "我今天学了语法", "美式和拿铁有什么区别？", "不要翻译这句话", "Where did you go?"))
            assertFalse(input, EnglishLearningQuestionPolicy.isQuestion(input, null));
    }

    /** 原样读出语言问题目标依旧属于练习；额外询问意思才是真实请求，ASR 丢标点也不改变边界。 */
    @Test public void repeatedKnowledgeQuestionTargetRemainsReading() {
        for (String target : List.of("How do I say hello?", "What does hello mean?", "What do you mean?")) {
            var previous = new TeachingTaskVO("READ", target, "", 0);
            var d = TeachingPolicy.decide(EnglishLevelEnum.L2, previous, target.replace("?", ""),
                    new PronunciationFeedbackVO(95., List.of()), true, 0);
            assertEquals(target, TeachingOutcomeEnum.READ_PASS, d.outcome());
            assertTrue(d.practiceInput());
            assertEquals(TeachingOutcomeEnum.ENGLISH_QUESTION,
                    TeachingPolicy.decide(EnglishLevelEnum.L2, previous, target + "这句话什么意思？", null, true, 0).outcome());
        }
    }

    /** 解答规则与例句可完整播报；不生硬接续跟读，也不把例句中的问句变成待回答任务。 */
    @Test public void explanationKeepsExamplesAndDoesNotRegisterTasks() {
        var d = TeachingPolicy.decide(EnglishLevelEnum.L2, null, "How are you 是什么意思？", null, true, 0);
        var spoken = String.join("", TeachingReplyGuard.apply(Flux.just(
                "❌这句话用于询问近况。", "“How are you?” 意思是：你好吗？。",
                "回答时可以说 I am fine.", "意思是：我很好。", "这是日常问候的用法。",
                "请跟读一次。"), d).collectList().block());
        assertTrue(spoken, spoken.contains("日常问候的用法"));
        assertTrue(spoken, spoken.contains("I am fine."));
        assertFalse(spoken.contains("❌"));
        assertFalse(spoken.contains("请跟读"));
        var pending = SpokenTaskResolver.resolve(d.nextTask(), spoken);
        assertFalse(pending.reading());
        assertEquals("", pending.question());
        var policy = ReplyPromptBuilder.buildSystemPolicy(d);
        assertTrue(policy.contains("优先直接回答用户当前的问题"));
        assertTrue(policy.contains("不要求跟读或重复"));
    }
}

package cn.adinall.ai.domain.conversation;

import cn.adinall.ai.domain.conversation.model.valobj.*;
import cn.adinall.ai.domain.conversation.service.reply.*;
import cn.adinall.ai.domain.learner.model.valobj.EnglishLevelEnum;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.*;

/** 连续跟读的真实播报任务回归：同句计数、换句、结束与新需求分别处理，不依赖真实模型。 */
public class ReadingTaskContextTest {
    private TeachingTaskVO read(int count) {
        return new TeachingTaskVO(TeachingActionEnum.READ.code(), "I cooked dinner.", "", count);
    }
    private PronunciationFeedbackVO feedback() {
        return new PronunciationFeedbackVO(90., List.of(new PronunciationFeedbackVO.IssueVO("cooked", "kʊkt", "kʊk", .95)));
    }

    /** 模型忽略 RESPOND 建议又邀读同句，也不能将次数清零；大小写与句末标点不构成换句。 */
    @Test public void repeatedInvitationNeverResetsSameTargetBudget() {
        var previous = read(0);
        for (int round = 1; round <= 5; round++) {
            previous = SpokenTaskResolver.resolve(TeachingTaskVO.response(), previous,
                    "更清楚的读法是：\"I COOKED dinner!\" 请再跟读一次。");
            assertTrue(previous.reading());
            assertEquals(Math.min(round, TeachingPolicy.MAX_READING_RETRIES), previous.attempts());
        }
        var next = TeachingPolicy.decide(EnglishLevelEnum.L2, previous, "I cooked dinner", feedback(), true);
        assertFalse(next.nextTask().reading());
        assertEquals("", next.correctionHint());
    }

    /** 正常两次可信纠音经过实际播报提交后消耗两次预算，不重复加算同一建议。 */
    @Test public void confirmedCorrectionsUseBudgetAndPromptExposesRemainingCount() throws Exception {
        var previous = read(0);
        for (int count = 1; count <= 2; count++) {
            var decision = TeachingPolicy.decide(EnglishLevelEnum.L2, previous, "I cooked dinner", feedback(), true);
            assertEquals(TeachingOutcomeEnum.READ_RETRY, decision.outcome());
            previous = SpokenTaskResolver.resolve(decision.nextTask(), previous, "\"I cooked dinner.\" 请再跟读一次。");
            assertEquals(count, previous.attempts());
        }
        var capped = TeachingPolicy.decide(EnglishLevelEnum.L2, previous, "I cooked dinner", feedback(), true);
        String turn = ReplyPromptBuilder.buildManaged("I cooked dinner", capped, null, previous, Map.of());
        var data = new ObjectMapper().readTree(turn.substring(turn.indexOf('>') + 1, turn.indexOf("</session_context>")));
        assertEquals(2, data.get("current_task").get("correction_retries").asInt());
        assertEquals(0, data.get("current_task").get("remaining_correction_retries").asInt());
        assertTrue(data.get("practice_input").asBoolean());
        assertFalse(capped.nextTask().reading());
    }

    /** 换句从零开始；没有明确邀请时结束任务，不因单纯示范增加重试。 */
    @Test public void newSentenceAndDemonstrationHaveSeparateLifecycles() {
        var next = SpokenTaskResolver.resolve(TeachingTaskVO.response(), read(2), "\"I like books.\" 请跟读一次。");
        assertTrue(next.reading()); assertEquals(0, next.attempts());
        assertFalse(SpokenTaskResolver.resolve(TeachingTaskVO.response(), read(1), "更清楚的读法是 cooked，接着聊晚餐吧。").reading());
    }

    /** 新知识问题不构成跟读输入，不能因旧目标与可信错词而强迫再读。 */
    @Test public void currentQuestionEscapesOldReadingTask() {
        var next = TeachingPolicy.decide(EnglishLevelEnum.L2, read(1), "cooked 为什么这样发音？", feedback(), true);
        assertFalse(next.practiceInput()); assertFalse(next.nextTask().reading()); assertEquals("", next.correctionHint());
    }
}

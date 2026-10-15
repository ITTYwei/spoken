package cn.adinall.ai.cases.conversation;

import cn.adinall.ai.domain.agent.model.valobj.ContextSectionVO;
import cn.adinall.ai.domain.agent.service.context.ChatContextService;
import cn.adinall.ai.domain.conversation.model.aggregate.*;
import cn.adinall.ai.domain.conversation.model.valobj.SessionStatusEnum;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.Instant;
import java.util.List;
import static org.junit.Assert.*;

/** 验证会话学习请求比普通历史优先，且是原话数据而不是强制教学命令或长期偏好。 */
public class LearningRequestContextTest {
    /** 真实 Case 组装 + 真实预算裁剪：后续“能举例吗”仍携带音标目标，即使旧历史全部被删。 */
    @Test public void requestSurvivesHistoryTrimmingAcrossTurns() {
        var preparation=TeachingTestFixture.create();
        ReflectionTestUtils.setField(preparation,"context",new ChatContextService(List.of(r -> List.of(
                new ContextSectionVO("ordinary-history",40,"irrelevant-history-".repeat(10000)))),10000));
        var session=new ConversationSessionAggregate("s","p","device","avatar",null,"voice","free",SessionStatusEnum.READY);
        session.bindTeachingSubject("user:1");
        preparation.decide(session,"我想学音标，音标有哪些？",null,true);
        var later=preparation.decide(session,"能举例吗？",null,true);
        String prompt=preparation.prompt(session,"能举例吗？",later,null);
        assertTrue(prompt,prompt.contains("\"会话学习请求原话\""));
        assertTrue(prompt,prompt.contains("我想学音标，音标有哪些？"));
        assertTrue(prompt,prompt.contains("能举例吗？"));
        assertFalse(prompt.contains("irrelevant-history-"));
        assertFalse(preparation.systemPolicy(session,later).contains("我想学音标，音标有哪些？"));
    }
    /** 只维护本场目标：补充对话不覆盖；新目标替换；明确结束清空；旧轮次不能覆盖新请求。 */
    @Test public void latestRequestReplacesFocusAndEndClearsIt() {
        var state=new TeachingStateAggregate();var time=Instant.parse("2026-10-14T10:00:00Z");
        state.captureLearningRequest("我想学音标",false,time);
        state.captureLearningRequest("能举例吗",false,time.plusSeconds(1));assertEquals("我想学音标",state.learningRequest());
        state.captureLearningRequest("我想学过去式",false,time.plusSeconds(2));assertEquals("我想学过去式",state.learningRequest());
        state.captureLearningRequest("我想学音标",false,time);assertEquals("我想学过去式",state.learningRequest());
        state.captureLearningRequest("我不想学了",false,time.plusSeconds(3));assertEquals("",state.learningRequest());
    }
    /** 跟读、引用、另一会话及取消播报不能把素材变成目标，也不能串会话。 */
    @Test public void exercisesAndOtherSessionsDoNotPolluteLearningFocus() {
        var state=new TeachingStateAggregate();var time=Instant.now();
        state.captureLearningRequest("I want to learn phonetics",true,time);assertEquals("",state.learningRequest());
        state.captureLearningRequest("跟读‘我想学音标’",false,time);assertEquals("",state.learningRequest());
        state.captureLearningRequest("我想学音标",false,time);state.interrupt();assertEquals("我想学音标",state.learningRequest());
        assertEquals("",new TeachingStateAggregate().learningRequest());
    }
}

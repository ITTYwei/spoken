package cn.adinall.ai.cases.conversation;

import cn.adinall.ai.domain.coaching.teaching.service.TeachingService;
import cn.adinall.ai.cases.teaching.TeachingPreparation;
import cn.adinall.ai.domain.coaching.teaching.model.valobj.TeachingDecisionVO;
import cn.adinall.ai.domain.content.model.valobj.TextLessonVO;
import cn.adinall.ai.domain.content.model.valobj.TextbookVO;
import com.fasterxml.jackson.databind.JsonNode;

import cn.adinall.ai.domain.coaching.conversation.model.aggregate.ConversationSessionAggregate;
import cn.adinall.ai.domain.coaching.conversation.model.valobj.*;
import cn.adinall.ai.domain.coaching.teaching.model.valobj.TeachingActionEnum;
import cn.adinall.ai.domain.coaching.teaching.model.valobj.TeachingTaskVO;
import cn.adinall.ai.domain.coaching.practice.service.PracticeService;
import cn.adinall.ai.domain.coaching.practice.adapter.repository.IRecentLessonRepository;
import cn.adinall.ai.domain.content.service.TextbookService;
import cn.adinall.ai.infrastructure.adapter.repository.ResourceTextbookRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** 实际 Case 组装场景和已播任务，避免只有脱离运行链路的提示词示例。 */
public class SceneTaskContextTest {
    /** 每个测试独立持有教学运行态，并显式共享给相关应用协作者。 */
    private final TeachingService teachingStates = new TeachingService();

    /** 课程场景中仍有独立的已播跟读任务、真实重试次数；当前新请求仍位于最后。 */
    @Test public void courseSceneAndSpokenReadingTaskAreBothVisible() throws Exception {
        TeachingPreparation preparation = TeachingTestFixture.create(teachingStates);
        ConversationSessionAggregate session = new ConversationSessionAggregate("s", "p", "device", "avatar", null, "voice", "free", SessionStatusEnum.READY);
        session.bindSubject("user:1");
        PracticeService practice = new PracticeService(mock(IRecentLessonRepository.class));
        TextbookService textbooks = new TextbookService(new ResourceTextbookRepository(new ObjectMapper()));
        TextbookVO book = textbooks.list().get(0); TextLessonVO lesson = book.lessons().get(0);
        practice.initialize("s", null, null, null);
        practice.state("s").change("COURSE", book.id(), lesson.id(), lesson.title(), "chosen", "chosen");
        ReflectionTestUtils.setField(preparation, "practice", practice);
        ReflectionTestUtils.setField(preparation, "textbooks", textbooks);
        teachingStates.state(session.getSessionId()).commit(new TeachingTaskVO(TeachingActionEnum.READ.code(), "I cooked dinner.", "", 1));
        TeachingDecisionVO decision = preparation.decide(session, "为什么 cooked 发这个音？", null, true);
        String prompt = preparation.prompt(session, "为什么 cooked 发这个音？", decision, "友好教练");
        JsonNode data = new ObjectMapper().readTree(prompt.substring(prompt.indexOf('>') + 1, prompt.indexOf("</session_context>")));
        assertEquals(1, data.get("current_task").get("correction_retries").asInt());
        assertEquals("文本课程陪练", data.get("background").get("对话场景").get("场景说明").asText());
        assertEquals("free", data.get("background").get("对话场景").get("场景编号").asText());
        assertTrue(data.get("background").get("当前文本课程数据").asText().contains(lesson.questions().get(0)));
        assertFalse(data.get("practice_input").asBoolean());
        assertTrue(prompt.endsWith("\"为什么 cooked 发这个音？\"\n</user_input>"));
    }

    /** 无课程也提供真实场景编号和背景说明，不能把角色练习冒充自由交流。 */
    @Test public void standaloneScenarioDoesNotDependOnPracticeRepository() throws Exception {
        TeachingPreparation preparation = TeachingTestFixture.create(teachingStates);
        ConversationSessionAggregate session = new ConversationSessionAggregate("s", "p", "device", "avatar", null, "voice", "restaurant", SessionStatusEnum.READY);
        session.bindSubject("user:1");
        TeachingDecisionVO decision = preparation.decide(session, "How do I order tea?", null, false);
        String prompt = preparation.prompt(session, "How do I order tea?", decision, "点餐练习");
        JsonNode data = new ObjectMapper().readTree(prompt.substring(prompt.indexOf('>') + 1, prompt.indexOf("</session_context>")));
        assertEquals("restaurant", data.get("background").get("对话场景").get("场景编号").asText());
        assertEquals("指定情境下的英语口语练习", data.get("background").get("对话场景").get("场景说明").asText());
    }
}

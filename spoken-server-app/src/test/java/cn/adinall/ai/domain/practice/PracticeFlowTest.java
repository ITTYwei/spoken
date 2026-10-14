package cn.adinall.ai.domain.practice;

import cn.adinall.ai.domain.practice.model.aggregate.PracticeAggregate;
import cn.adinall.ai.domain.scenario.service.TextbookService;
import cn.adinall.ai.infrastructure.adapter.repository.ResourceTextbookRepository;
import cn.adinall.ai.types.exception.AppException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import static org.junit.Assert.*;

/** 验证对真实练习有影响的版本、去重、播报边界和完整教材，不连接模型或媒体。 */
public class PracticeFlowTest {
    /** 清晰的自由聊天意图不能被工具反向解释成继续课程，英语缩写不误判为引用。 */
    @Test public void explicitFreeIntentAndContractionsAreRespected() {
        assertFalse(cn.adinall.ai.domain.practice.service.PracticeIntentPolicy.allowsSwitch("No, let's just chat normally","COURSE",true));
        assertTrue(cn.adinall.ai.domain.practice.service.PracticeIntentPolicy.allowsSwitch("No, let's just chat normally","FREE",true));
        assertFalse(cn.adinall.ai.domain.practice.service.PracticeIntentPolicy.quotedOrRole("I don't like coffee"));
        assertFalse(cn.adinall.ai.domain.practice.service.PracticeIntentPolicy.forgetRequest("我忘了带钥匙"));
        assertTrue(cn.adinall.ai.domain.practice.service.PracticeIntentPolicy.forgetRequest("请忘掉咖啡"));
    }
    /** 同一动作重试不会再次执行；标识复用其他参数和旧版本动作均被拒绝。 */
    @Test public void duplicateAndStaleOperationsCannotSwitchAgain() {
        var state = new PracticeAggregate(null,null,null);
        assertTrue(state.check("op1","COURSE:a:b",0));
        state.change("COURSE","a","b","课","op1","COURSE:a:b");
        assertFalse(state.check("op1","COURSE:a:b",0));
        assertEquals(1,state.snapshot().version());
        assertThrows(AppException.class,()->state.check("op1","FREE",1));
        assertThrows(AppException.class,()->state.check("op2","FREE",0));
        assertEquals("COURSE",state.snapshot().mode());
    }
    /** 继续询问必须完整播完，打断及切换后的旧完成回执不能打开“好”的选择窗口。 */
    @Test public void continuationIsBoundToHeardReplyAndVersion() {
        var state = new PracticeAggregate("a","b","课");
        state.invite("opening"); assertFalse(state.snapshot().pendingContinue());
        assertFalse(state.heard("another")); assertTrue(state.heard("opening"));
        assertTrue(state.snapshot().pendingContinue());
        state.dismissInvitation(); assertFalse(state.snapshot().pendingContinue());
        state.invite("old"); state.change("FREE",null,null,null,"op","FREE");
        assertFalse(state.heard("old"));
    }
    /** 自由模式保留最近课推荐，但当前课为空；新会话不恢复具体题号。 */
    @Test public void freeModeKeepsOnlyRecentReference() {
        var state = new PracticeAggregate(null,null,null);
        state.change("COURSE","a","b","课","one","course");
        state.change("FREE",null,null,null,"two","free");
        assertNull(state.snapshot().lessonId()); assertEquals("b",state.snapshot().recentLessonId());
        assertEquals("FREE",new PracticeAggregate("a","b","课").snapshot().mode());
    }
    /** 发布教材包含三组完整原题，并且每课可全量进入当前预算，未知课不能被猜测补全。 */
    @Test public void bundledCoursesAreCompleteAndFitRequiredContext() {
        var textbooks=new TextbookService(new ResourceTextbookRepository(new ObjectMapper()));
        var book=textbooks.list().get(0);
        assertEquals("CC0-1.0",book.license()); assertEquals(3,book.lessons().size());
        assertEquals(16,book.lessons().stream().mapToInt(l->l.questions().size()).sum());
        book.lessons().forEach(l->assertEquals(l,textbooks.lesson(book.id(),l.id())));
        assertThrows(AppException.class,()->textbooks.lesson(book.id(),"invented"));
    }
    /** 超预算教材拒绝整课，不能静默剪掉尾题。 */
    @Test public void oversizedLessonIsRejected() {
        var lesson=new cn.adinall.ai.domain.scenario.model.valobj.TextLessonVO("long","长课","目标","source",java.util.List.of("x".repeat(2000)),"guidance");
        var book=new cn.adinall.ai.domain.scenario.model.valobj.TextbookVO("b","教材","source","version","CC0",java.util.List.of(lesson));
        assertThrows(AppException.class,()->new TextbookService(()->java.util.List.of(book)).lesson("b","long"));
    }
}

package cn.adinall.ai.domain.agent;

import cn.adinall.ai.domain.agent.service.memory.AbilityEvidenceService;
import cn.adinall.ai.domain.agent.service.armory.factory.DefaultArmoryFactory;
import cn.adinall.ai.domain.agent.model.valobj.AiAgentRegisterVO;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.junit.Test;
import reactor.core.publisher.Flux;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/**
 * 功能：验证后台观察严格JSON和有界并发，不抢占全部对话资源。
 * <p>体系架构 / 运行过程：</p>
 * <pre>mock model stream -> observation parse -> bounded request/cancel assertions</pre>
 * <p>案例：第三个在途观察立即放弃，两个取消都传给模型。</p>
 * <p>设计要点：</p>
 * <ul><li>测试解析失败不升级。</li><li>不使用真实网络。</li></ul>
 */
public class AbilityEvidenceTest {
    private AbilityEvidenceService service(ChatModel model) {
        var factory=mock(DefaultArmoryFactory.class);
        when(factory.getAiAgentRegisterVO("a")).thenReturn(AiAgentRegisterVO.builder().chatModel(model).build());
        return new AbilityEvidenceService(factory);
    }
    private Flux<ChatResponse> response(String text) { return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage(text))))); }
    @Test public void validObservationDoesNotModifyGradeItself() {
        var model=mock(ChatModel.class); when(model.stream(any(Prompt.class))).thenReturn(response("{\"outcome\":\"ANSWERED\",\"candidate\":\"L3\",\"confidence\":0.9,\"independent\":true}"));
        var value=service(model).observe("a","What food?","I like noodles").block(Duration.ofSeconds(3));
        assertEquals("ANSWERED",value.outcome()); assertEquals("L3",value.candidate());
    }
    @Test public void invalidJsonIsDiscarded() {
        var model=mock(ChatModel.class); when(model.stream(any(Prompt.class))).thenReturn(response("Sure! Your English is excellent."));
        assertNull(service(model).observe("a","What food?","Yes").block(Duration.ofSeconds(3)));
    }
    @Test public void stringConfidenceIsNotTrusted() {
        var model=mock(ChatModel.class); when(model.stream(any(Prompt.class))).thenReturn(response("{\"outcome\":\"ANSWERED\",\"candidate\":\"L6\",\"confidence\":\"0.99\",\"independent\":true}"));
        assertNull(service(model).observe("a","What food?","Yes").block(Duration.ofSeconds(3)));
    }
    @Test public void onlyTwoRequestsAndCancellationReleasesSlots() throws Exception {
        var model=mock(ChatModel.class); var started=new AtomicInteger(); var cancelled=new AtomicInteger();
        when(model.stream(any(Prompt.class))).thenReturn(Flux.<ChatResponse>never().doOnSubscribe(s -> started.incrementAndGet()).doOnCancel(cancelled::incrementAndGet));
        var service=service(model); var first=service.observe("a","Q1?","A1").subscribe(); var second=service.observe("a","Q2?","A2").subscribe();
        long deadline=System.nanoTime()+Duration.ofSeconds(3).toNanos();
        while(started.get()<2 && System.nanoTime()<deadline) Thread.sleep(5);
        assertEquals(2,started.get()); assertNull(service.observe("a","Q3?","A3").block(Duration.ofSeconds(1)));
        first.dispose(); second.dispose();
        deadline=System.nanoTime()+Duration.ofSeconds(3).toNanos();
        while(cancelled.get()<2 && System.nanoTime()<deadline) Thread.sleep(5);
        assertEquals(2,cancelled.get());
    }
}

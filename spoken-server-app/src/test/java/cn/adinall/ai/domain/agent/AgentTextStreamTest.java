package cn.adinall.ai.domain.agent;

import cn.adinall.ai.domain.agent.service.chat.AgentTextStream;
import cn.adinall.ai.types.exception.AppException;
import com.google.adk.events.Event;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import org.junit.Test;
import reactor.core.publisher.Flux;
import java.util.List;
import java.util.Optional;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * Agent 文本转换契约测试。
 * <p>功能：验证用户回显过滤、增量去重、错误传播及订阅隔离。
 * <p>体系架构 / 运行过程：
 * <pre>
 *   构造 ADK 事件 -> AgentTextStream -> 文本序列 / 错误断言
 * </pre>
 * 案例：
 * <pre>
 *   输入：用户 Hi、模型 Hel + lo、汇总 Hello -> 输出：[Hel, lo]。
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>测试真实事件转换，模型和网络均不参与。</li>
 *   <li>相同流重复订阅也必须得到完整输出。</li>
 * </ul>
 */
public class AgentTextStreamTest {
    /** 模拟 ADK 文本事件，明确所有 Optional 字段的值。 */
    private Event event(String author, String text, boolean partial) {
        Event event = mock(Event.class);
        when(event.author()).thenReturn(author);
        when(event.errorMessage()).thenReturn(Optional.empty());
        when(event.partial()).thenReturn(Optional.of(partial));
        when(event.content()).thenReturn(text == null ? Optional.empty()
                : Optional.of(Content.fromParts(Part.fromText(text))));
        return event;
    }

    @Test public void removesUserEchoAndFinalSummary() {
        var text = AgentTextStream.from(Flux.just(event("user", "Hi", false),
                event("coach", "Hel", true), event("coach", "lo", true),
                event("coach", "Hello", false))).collectList().block();
        assertEquals(List.of("Hel", "lo"), text);
    }

    @Test public void nonStreamingResponseIsKeptOnce() {
        assertEquals(List.of("Hello"), AgentTextStream.from(Flux.just(
                event("coach", "Hello", false), event("coach", "Hello", false))).collectList().block());
    }

    @Test public void toolOnlyEventDoesNotConsumeFinalText() {
        assertEquals(List.of("Hello"), AgentTextStream.from(Flux.just(
                event("coach", null, false), event("coach", "Hello", false))).collectList().block());
    }

    @Test public void eachSubscriptionHasIndependentState() {
        var text = AgentTextStream.from(Flux.just(event("coach", "Hello", false)));
        assertEquals(List.of("Hello"), text.collectList().block());
        assertEquals(List.of("Hello"), text.collectList().block());
    }

    @Test(expected = AppException.class) public void modelErrorIsPropagated() {
        Event event = event("coach", null, false);
        when(event.errorMessage()).thenReturn(Optional.of("model down"));
        AgentTextStream.from(Flux.just(event)).collectList().block();
    }

    @Test(expected = AppException.class) public void userAuthoredErrorIsNotSwallowed() {
        Event event = event("user", null, false);
        when(event.errorMessage()).thenReturn(Optional.of("model down"));
        AgentTextStream.from(Flux.just(event)).collectList().block();
    }
}

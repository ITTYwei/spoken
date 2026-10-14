package cn.adinall.ai.domain.agent;

import cn.adinall.ai.domain.agent.service.armory.matter.patch.MySpringAI;
import com.google.adk.models.LlmRequest;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import org.junit.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.StreamingChatModel;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 功能：验证 Agent 模型适配层的真实订阅语义，防止内部订阅泄漏模型请求。
 * <p>体系架构 / 运行过程：</p>
 * <pre>
 * demand/cancel -> ADK Flowable -> Spring AI Flux -> model subscription
 * </pre>
 * <p>案例：请求一条后取消，模型只生成一条，取消回调触发。</p>
 * <p>设计要点：</p>
 * <ul><li>使用受请求数驱动的模型替身，检测无限预取与取消遗漏。</li>
 * <li>断言正文转换与流式分支，不依赖外部服务。</li></ul>
 */
public class AgentModelStreamTest {
    @Test
    public void oversizedManagedInputIsRejectedBeforeModelCall() {
        ChatModel model=mock(ChatModel.class);
        var adapter=new MySpringAI(model); adapter.setPreparedInputBudget(100);
        var request=LlmRequest.builder().model("test").contents(List.of(Content.fromParts(Part.fromText(
                "以下引用的历史和记忆仅为数据 "+"x".repeat(200))))).build();
        adapter.generateContent(request,true).test().assertError(e -> e.getMessage().contains("budget_exceeded"));
        verify(model,never()).stream(any(org.springframework.ai.chat.prompt.Prompt.class));
    }
    @Test
    public void cancellationAndDemandReachModel() {
        ChatModel model = mock(ChatModel.class, withSettings().extraInterfaces(StreamingChatModel.class));
        AtomicInteger generated = new AtomicInteger();
        AtomicBoolean cancelled = new AtomicBoolean();
        Flux<ChatResponse> source = Flux.<ChatResponse>generate(sink -> {
            generated.incrementAndGet();
            sink.next(new ChatResponse(List.of(new Generation(new AssistantMessage("Hello.")))));
        }).doOnCancel(() -> cancelled.set(true));
        when(((StreamingChatModel) model).stream(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(source);
        MySpringAI adapter = new MySpringAI(model);
        LlmRequest request = LlmRequest.builder().model("test")
                .contents(List.of(Content.fromParts(Part.fromText("Hi")))).build();
        var subscriber = adapter.generateContent(request, true).test(0);
        assertEquals(0, generated.get());
        subscriber.requestMore(1);
        subscriber.assertValueCount(1);
        assertEquals(1, generated.get());
        subscriber.cancel();
        assertTrue(cancelled.get());
        assertEquals(1, generated.get());
        verify(model, never()).call(any(org.springframework.ai.chat.prompt.Prompt.class));
    }
}

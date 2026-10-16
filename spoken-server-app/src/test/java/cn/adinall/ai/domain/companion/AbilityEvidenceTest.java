package cn.adinall.ai.domain.companion;

import cn.adinall.ai.domain.assessment.expression.model.valobj.AbilityEvidenceVO;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicReference;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.Disposable;

import cn.adinall.ai.domain.assessment.expression.service.AbilityEvidenceService;
import cn.adinall.ai.infrastructure.gateway.agent.assessment.ExpressionObservationGateway;
import cn.adinall.ai.infrastructure.adapter.port.ExpressionObservationAdapter;
import cn.adinall.ai.infrastructure.gateway.agent.armory.factory.DefaultArmoryFactory;
import cn.adinall.ai.infrastructure.gateway.agent.model.AiAgentRegisterVO;
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
        DefaultArmoryFactory factory=mock(DefaultArmoryFactory.class);
        when(factory.getAiAgentRegisterVO("a")).thenReturn(AiAgentRegisterVO.builder().chatModel(model).build());
        return new AbilityEvidenceService(new ExpressionObservationAdapter(new ExpressionObservationGateway(factory)));
    }
    private Flux<ChatResponse> response(String text) { return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage(text))))); }
    @Test public void validObservationDoesNotModifyGradeItself() {
        ChatModel model=mock(ChatModel.class); when(model.stream(any(Prompt.class))).thenReturn(response("{\"outcome\":\"ANSWERED\",\"candidate\":\"L3\",\"confidence\":0.9,\"independent\":true}"));
        AbilityEvidenceVO value=service(model).observe("a","What food?","I like noodles").block(Duration.ofSeconds(3));
        assertEquals("ANSWERED",value.outcome()); assertEquals("L3",value.candidate());
        ArgumentCaptor<Prompt> captured = org.mockito.ArgumentCaptor.forClass(Prompt.class);
        verify(model).stream(captured.capture());
        OpenAiChatOptions options = (org.springframework.ai.openai.OpenAiChatOptions) captured.getValue().getOptions();
        assertEquals("none", options.getToolChoice());
        assertEquals(Boolean.FALSE, options.getInternalToolExecutionEnabled());
    }

    /**
     * 使用真实 Spring AI 模型和本地供应商替身，验证默认回调合并后也不能执行后台业务工具。
     * 替身故意违反 none 返回工具调用，观察结果应被丢弃，不重试、不执行工具、不依赖账号线程变量。
     */
    @Test public void observerCannotExecuteInheritedToolsEvenWhenProviderReturnsToolCalls() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> requestBody = new java.util.concurrent.atomic.AtomicReference<String>();
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requests.incrementAndGet();
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            String response = "data: {\"id\":\"bg\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"test\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"tool_calls\":[{\"index\":0,\"id\":\"call_bg\",\"type\":\"function\",\"function\":{\"name\":\"unsafeBackgroundTool\",\"arguments\":\"{}\"}}]},\"finish_reason\":null}]}\n\n"
                    + "data: {\"id\":\"bg\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"test\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\n"
                    + "data: [DONE]\n\n";
            byte[] bytes = response.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start();
        try {
            ToolCallback[] callbacks = org.springframework.ai.tool.method.MethodToolCallbackProvider.builder()
                    .toolObjects(new ObserverToolTrap(calls)).build().getToolCallbacks();
            OpenAiChatOptions defaults = org.springframework.ai.openai.OpenAiChatOptions.builder().model("test")
                    .toolCallbacks(List.of(callbacks)).toolChoice("auto").internalToolExecutionEnabled(true).build();
            OpenAiApi api = org.springframework.ai.openai.api.OpenAiApi.builder()
                    .baseUrl("http://127.0.0.1:" + server.getAddress().getPort()).apiKey("test-only").build();
            OpenAiChatModel model = org.springframework.ai.openai.OpenAiChatModel.builder().openAiApi(api).defaultOptions(defaults).build();
            assertNull(service(model).observe("a", "What food?", "I like noodles").block(Duration.ofSeconds(10)));
            assertEquals(1, requests.get());
            assertEquals(0, calls.get());
            assertEquals("none", new com.fasterxml.jackson.databind.ObjectMapper().readTree(requestBody.get()).path("tool_choice").asText());
            assertEquals("auto", defaults.getToolChoice());
            assertEquals(Boolean.TRUE, defaults.getInternalToolExecutionEnabled());
        } finally { server.stop(0); }
    }

    /** 只供隔离测试的业务工具陷阱，模拟生产工具必须接收 ToolContext 的签名。 */
    public static class ObserverToolTrap {
        /** 被执行次数；纯后台观察始终必须为零。 */
        private final AtomicInteger calls;
        public ObserverToolTrap(AtomicInteger calls) { this.calls = calls; }
        /** 模型违约返回此调用时，客户端工具执行开关必须先阻止回调。 */
        @org.springframework.ai.tool.annotation.Tool(description = "测试后台业务工具隔离，不得由能力观察调用。")
        public String unsafeBackgroundTool(org.springframework.ai.chat.model.ToolContext context) {
            calls.incrementAndGet();
            return "不应执行";
        }
    }
    @Test public void invalidJsonIsDiscarded() {
        ChatModel model=mock(ChatModel.class); when(model.stream(any(Prompt.class))).thenReturn(response("Sure! Your English is excellent."));
        assertNull(service(model).observe("a","What food?","Yes").block(Duration.ofSeconds(3)));
    }
    @Test public void stringConfidenceIsNotTrusted() {
        ChatModel model=mock(ChatModel.class); when(model.stream(any(Prompt.class))).thenReturn(response("{\"outcome\":\"ANSWERED\",\"candidate\":\"L6\",\"confidence\":\"0.99\",\"independent\":true}"));
        assertNull(service(model).observe("a","What food?","Yes").block(Duration.ofSeconds(3)));
    }
    @Test public void onlyTwoRequestsAndCancellationReleasesSlots() throws Exception {
        ChatModel model=mock(ChatModel.class); AtomicInteger started=new AtomicInteger(); AtomicInteger cancelled=new AtomicInteger();
        when(model.stream(any(Prompt.class))).thenReturn(Flux.<ChatResponse>never().doOnSubscribe(s -> started.incrementAndGet()).doOnCancel(cancelled::incrementAndGet));
        AbilityEvidenceService service=service(model); Disposable first=service.observe("a","Q1?","A1").subscribe(); Disposable second=service.observe("a","Q2?","A2").subscribe();
        long deadline=System.nanoTime()+Duration.ofSeconds(3).toNanos();
        while(started.get()<2 && System.nanoTime()<deadline) Thread.sleep(5);
        assertEquals(2,started.get()); assertNull(service.observe("a","Q3?","A3").block(Duration.ofSeconds(1)));
        first.dispose(); second.dispose();
        deadline=System.nanoTime()+Duration.ofSeconds(3).toNanos();
        while(cancelled.get()<2 && System.nanoTime()<deadline) Thread.sleep(5);
        assertEquals(2,cancelled.get());
    }
}

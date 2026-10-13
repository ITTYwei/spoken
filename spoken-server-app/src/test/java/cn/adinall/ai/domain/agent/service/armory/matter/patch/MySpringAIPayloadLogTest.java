package cn.adinall.ai.domain.agent.service.armory.matter.patch;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.google.adk.models.LlmRequest;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import org.junit.Test;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.StreamingChatModel;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 「发给 LLM 的内容」打印契约测试。
 * <p>
 * 功能：验证 {@code module.chat-model.log-llm-payload} 打开时，模型适配器会把转换后的
 * Prompt（系统指令 + 历史消息）逐条打进日志；关闭时一条都不打。
 * <pre>
 *   LlmRequest(systemInstruction + user 文本) -> MySpringAI.generateContent(stream) -> 日志
 * </pre>
 * 案例：
 * <pre>
 *   输入: 系统指令 "You are SpokenCoach"、用户消息 "I play frisbee"
 *   输出: 日志含 "发给 LLM 的请求 … messages=2"，以及 SYSTEM / USER 两条完整文本
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>模型用假的 StreamingChatModel 顶掉：本测试只关心「打印了什么」，不连网。</li>
 *   <li>用真实的 {@link LlmRequest} 而不是 mock：SystemMessage 是 MessageConverter 在这一步
 *       拼出来的，mock 掉就看不出「打印的到底是不是模型实际看到的内容」。</li>
 *   <li>两条断言成对存在：打开必须打、关闭必须不打 —— 少一条就会变成「日志开关永远为真」。</li>
 * </ul>
 */
public class MySpringAIPayloadLogTest {

    /** 打开开关：请求头 + 每条 message 的完整文本都要在日志里。 */
    @Test
    public void logsPromptContentWhenEnabled() {
        MySpringAI model = new MySpringAI(echoModel(), "bonsai-2-27b");
        model.setLogLlmPayload(true);

        String logs = capture(() -> model.generateContent(request(), true).toList().blockingGet());

        assertTrue("缺少请求头: " + logs, logs.contains("发给 LLM 的请求"));
        assertTrue("请求头缺少 model", logs.contains("bonsai-2-27b"));
        assertTrue("请求头缺少消息条数", logs.contains("messages=2"));
        assertTrue("系统指令没打出来", logs.contains("You are SpokenCoach"));
        assertTrue("用户消息没打出来", logs.contains("I play frisbee"));
        assertTrue("没打角色", logs.contains("SYSTEM") && logs.contains("USER"));
    }

    /** 关闭开关（默认）：不得产生任何相关日志。 */
    @Test
    public void logsNothingWhenDisabled() {
        MySpringAI model = new MySpringAI(echoModel(), "bonsai-2-27b");

        String logs = capture(() -> model.generateContent(request(), true).toList().blockingGet());

        assertFalse("开关关闭时不应打印提示词: " + logs, logs.contains("发给 LLM"));
    }

    /** 一次真实的 ADK 请求：一条系统指令 + 一句用户消息。 */
    private static LlmRequest request() {
        return LlmRequest.builder()
                .model("bonsai-2-27b")
                .contents(List.of(Content.fromParts(Part.fromText("I play frisbee"))))
                .appendInstructions(List.of("You are SpokenCoach, keep every reply short."))
                .build();
    }

    /** 假模型：无论收到什么都回一句 "ok"，只为让请求跑完整条路径。 */
    private static StreamingChatModel echoModel() {
        return prompt -> Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("ok")))));
    }

    /** 在只挂本测试 appender 的日志器上跑一次调用，返回格式化后的日志文本。 */
    private static String capture(Runnable call) {
        Logger logger = (Logger) LoggerFactory.getLogger(MySpringAI.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            call.run();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).collect(Collectors.joining("\n"));
    }
}

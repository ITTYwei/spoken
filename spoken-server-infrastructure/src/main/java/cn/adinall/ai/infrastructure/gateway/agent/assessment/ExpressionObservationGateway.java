package cn.adinall.ai.infrastructure.gateway.agent.assessment;

import org.springframework.ai.chat.model.ChatModel;

import cn.adinall.ai.infrastructure.gateway.agent.armory.factory.DefaultArmoryFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.model.StreamingChatModel;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.Semaphore;

/**
 * <b>功能</b>：通过 Spring AI 流式模型调用取得有界观察文本。
 * <p><b>体系架构 / 运行过程</b></p>
 * <pre>tryAcquire(2) -> isolated prompt -> model.stream -> bounded text -> release</pre>
 * <p><b>案例</b>：已有两条观察在途 -> 第三条返回空；供应商返回工具调用 -> 不执行工具。</p>
 * <b>设计要点</b>
 * <ul><li>不定义评测档位和教学判断，指令由领域传入。</li>
 * <li>使用现有 Spring AI 框架流，不手写 HTTP/SSE 解析。</li>
 * <li>取消、超时及失败均释放容量，始终关闭后台工具执行。</li></ul>
 */
@Service
public class ExpressionObservationGateway {
    /**
     * 只读装配产物，模型调用归 Agent。
     */
    private final DefaultArmoryFactory factory;
    /**
     * 限制后台请求并发，避免抢占对话资源。
     */
    private final Semaphore slots = new Semaphore(2);
    /**
     * JSON 编解码器，不执行用户文本。
     */
    private final ObjectMapper json = new ObjectMapper();

    /** 创建 {@code ExpressionObservationGateway}，初始化其依赖与状态。 */
    public ExpressionObservationGateway(DefaultArmoryFactory factory) {
        this.factory = factory;
    }

    /** 根据问题与回答提取表达能力证据，不直接修改学习档位。 */
    public Mono<String> observe(String agentId, String instruction, String question, String answer) {
        return Mono.defer(() -> {
            if (question == null || question.isBlank() || answer == null || answer.isBlank() || !slots.tryAcquire())
                return Mono.empty();
            return Mono.defer(() -> {
                ChatModel model = factory.getAiAgentRegisterVO(agentId).getChatModel();
                if (model == null) return Mono.empty();
                StreamingChatModel streaming = model;
                String payload;
                try {
                    payload = json.writeValueAsString(Map.of("previousQuestion", question, "answer", answer));
                } catch (Exception e) {
                    return Mono.empty();
                }
                Prompt prompt = new Prompt(List.of(new SystemMessage(instruction), new UserMessage(payload)),
                        // 装配模型默认带业务回调；空回调列表会与默认列表合并，不能解除工具权限。
                        // 后台观察只允许返回证据 JSON：服务端声明 none，同时禁止客户端执行工具。
                        // 即使供应商仍返回工具调用，也仅按无效观察丢弃，不能进入个人设置或课程能力。
                        OpenAiChatOptions.builder().toolCallbacks(List.of()).toolNames(Set.of())
                                .toolChoice("none").internalToolExecutionEnabled(false).build());
                return streaming.stream(prompt).map(r -> r.getResult() == null || r.getResult().getOutput().getText() == null
                                ? "" : r.getResult().getOutput().getText())
                        .reduce(new StringBuilder(), (b, s) -> {
                            if (b.length() + s.length() > 2048)
                                throw new IllegalStateException("observation_too_large");
                            return b.append(s);
                        })
                        .map(StringBuilder::toString).timeout(Duration.ofSeconds(15));
            }).subscribeOn(Schedulers.boundedElastic()).doFinally(signal -> slots.release()).onErrorResume(e -> Mono.empty());
        });
    }

}

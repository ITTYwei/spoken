package cn.adinall.ai.domain.agent.service.armory.matter.patch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.models.BaseLlm;
import com.google.adk.models.BaseLlmConnection;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.models.springai.MessageConverter;
import com.google.adk.models.springai.error.SpringAIErrorMapper;
import com.google.adk.models.springai.observability.SpringAIObservabilityHandler;
import com.google.adk.models.springai.properties.SpringAIProperties;
import io.reactivex.rxjava3.core.Flowable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.StreamingChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Objects;

/**
 * Spring AI 适配器（ADK BaseLlm 与 Spring AI ChatModel 之间的桥）
 * <p>
 * 功能：让 ADK 侧的智能体直接使用一个 Spring AI 的对话模型 —— 把 LlmRequest 转成 Spring AI 的
 * Prompt，调 ChatModel / StreamingChatModel，再把 ChatResponse 转回 LlmResponse。
 * <pre>
 *   generateContent(LlmRequest, stream)  stream=true 走流式，false 走一次调用；
 *                                        对应模型缺失时返回 Flowable.error 而不抛异常
 *   connect(LlmRequest)                 永远抛 UnsupportedOperationException（不支持实时连接）
 *   model()                             父类持有并对外暴露的模型名（缺省时由类名推导）
 * </pre>
 * 体系架构（一次对话的完整链路）：
 * <pre>
 *   AgentNode.doApply(): LlmAgent.builder().model(new MySpringAI(chatModel))
 *        |
 *        v
 *   ADK Runner 执行 LlmAgent
 *        |
 *        v
 *   MySpringAI.generateContent(llmRequest, stream)   <-- 本类
 *        |
 *        | (1) prompt = messageConverter.toLlmPrompt(llmRequest)
 *        |
 *        | (2) stream ? streamingChatModel.stream(prompt) : chatModel.call(prompt)
 *        |
 *        | (3) ChatResponse --> messageConverter.toLlmResponse(...) --> LlmResponse
 *        |
 *        | (4) SpringAIObservabilityHandler: logRequest / logResponse / recordSuccess / recordError
 *        v
 *   Flowable&lt;LlmResponse&gt; 交回 ADK
 * </pre>
 * 运行过程（非流式分支，代码里的实际步骤）：
 * <pre>
 *   generateContent(llmRequest, false)
 *        |
 *        +--> chatModel == null
 *        |        |
 *        |        v
 *        |     Flowable.error(IllegalStateException("ChatModel is not configured"))
 *        |
 *        v
 *   generateContent(llmRequest)
 *        |
 *        | (1) observabilityHandler.startRequest(model(), "chat")
 *        | (2) prompt = messageConverter.toLlmPrompt(llmRequest); logRequest(prompt, model())
 *        | (3) chatResponse = chatModel.call(prompt)
 *        | (4) llmResponse = messageConverter.toLlmResponse(chatResponse)
 *        | (5) logResponse(extractTextFromResponse(llmResponse), model())
 *        | (6) 统计 token: getUsage().getTotalTokens() / getPromptTokens() / getCompletionTokens()
 *        |        （取不到时 extractXxxTokenCount 统一返回 0）
 *        | (7) observabilityHandler.recordSuccess(context, total, input, output)
 *        v
 *   Flowable.just(llmResponse)
 *   异常路径: recordError(context, e) --> SpringAIErrorMapper.mapError(e)
 *             --> Flowable.error(new RuntimeException(mappedError.getNormalizedMessage(), e))
 * </pre>
 * 案例：
 * <pre>
 *   输入: new MySpringAI(openAiChatModel, "bonsai-2-27b")（AgentNode 使用配置模型名），LlmRequest 为一句用户文本
 *   处理: generateContent(llmRequest, false) --> chatModel.call(prompt) --> toLlmResponse
 *   输出: Flowable 发出一个 LlmResponse；模型名为配置中的 "bonsai-2-27b"
 *   失败例: 用 new MySpringAI(streamingChatModel) 构造后调 generateContent(req, false)，
 *         此时 chatModel 为 null
 *         --> Flowable.error(IllegalStateException("ChatModel is not configured"))
 *   失败例: connect(llmRequest) --> 抛 UnsupportedOperationException(
 *         "Live connection is not supported for Spring AI models.")
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>适配方向是单向的：ADK 只认 BaseLlm，Spring AI 只认 ChatModel / StreamingChatModel，
 *       本类把二者接起来，并把消息格式转换下沉给 MyMessageConverter，自己只做流程编排。</li>
 *   <li>构造器有七个，差别只在「谁是主模型、modelName 从哪来、观测配置是否外部传入」；
 *       每个构造器都用 instanceof 反向探测对方的类型（ChatModel 也可能同时是 StreamingChatModel），
 *       所以选哪个构造器，决定了后续哪个分支可用。</li>
 *   <li>缺模型时不抛异常而返回 Flowable.error：错误要等订阅时才出现 ——
 *       因此用错构造器不会在装配期报错，只会在第一次对话时暴露。</li>
 *   <li>modelName 缺省时用 extractModelName 从类名推（"OpenAiChatModel" 去掉 chatmodel / model 后得 "openai"）：
 *       这只是兜底，需要准确模型名时应走带 modelName 的构造器。</li>
 *   <li>异常统一经 SpringAIErrorMapper.mapError 归一化后再包成 RuntimeException 抛出，并保留原始 cause；
 *       观测处理器在转换、调用、成功、失败各记一笔，便于判断失败发生在哪一步。</li>
 *   <li>不传观测配置时走 createDefaultObservabilityConfig()：enabled=true、metricsEnabled=true、
 *       includeContent=false（默认不把提示词内容写进观测输出）。</li>
 *   <li>「发给模型的内容」由本类自己打印（{@link #setLogLlmPayload(boolean)}，来自
 *       module.chat-model.log-llm-payload）：ADK 自带的 logRequest 是 DEBUG 且会截断内容，
 *       排查提示词时看不见全貌；这里打在 INFO、一条 message 一行、文本不截断。</li>
 *   <li>connect() 直接抛不支持：实时双向连接在本适配器上没有实现，需要该能力必须另写适配器。</li>
 * </ul>
 *
 * @author 魏玉石
 * 2026/1/9 08:20
 */
public class MySpringAI extends BaseLlm {

    private static final Logger log = LoggerFactory.getLogger(MySpringAI.class);

    private final ChatModel chatModel;
    private final StreamingChatModel streamingChatModel;
    private final ObjectMapper objectMapper;
    private final MessageConverter messageConverter;
    private final SpringAIObservabilityHandler observabilityHandler;

    /** 是否把发给模型的内容打进日志；由 AgentNode 按 module.chat-model.log-llm-payload 设置。 */
    private boolean logLlmPayload;
    /** 已管理教学请求的输入预算，出模型前再次校验实际系统指令。 */
    private int preparedInputBudget = 6000;

    public void setPreparedInputBudget(int budget) { this.preparedInputBudget = budget; }

    /**
     * 打开/关闭「打印发给 LLM 的内容」。装配期（AgentNode）设置一次，运行期只读。
     */
    public void setLogLlmPayload(boolean logLlmPayload) {
        this.logLlmPayload = logLlmPayload;
    }

    public MySpringAI(ChatModel chatModel) {
        super(extractModelName(chatModel));
        this.chatModel = Objects.requireNonNull(chatModel, "chatModel cannot be null");
        this.streamingChatModel =
                (chatModel instanceof StreamingChatModel) ? (StreamingChatModel) chatModel : null;
        this.objectMapper = new ObjectMapper();
        this.messageConverter = new MyMessageConverter(objectMapper);
        this.observabilityHandler =
                new SpringAIObservabilityHandler(createDefaultObservabilityConfig());
    }

    public MySpringAI(ChatModel chatModel, String modelName) {
        super(Objects.requireNonNull(modelName, "model name cannot be null"));
        this.chatModel = Objects.requireNonNull(chatModel, "chatModel cannot be null");
        this.streamingChatModel =
                (chatModel instanceof StreamingChatModel) ? (StreamingChatModel) chatModel : null;
        this.objectMapper = new ObjectMapper();
        this.messageConverter = new MyMessageConverter(objectMapper);
        this.observabilityHandler =
                new SpringAIObservabilityHandler(createDefaultObservabilityConfig());
    }

    public MySpringAI(StreamingChatModel streamingChatModel) {
        super(extractModelName(streamingChatModel));
        this.chatModel =
                (streamingChatModel instanceof ChatModel) ? (ChatModel) streamingChatModel : null;
        this.streamingChatModel =
                Objects.requireNonNull(streamingChatModel, "streamingChatModel cannot be null");
        this.objectMapper = new ObjectMapper();
        this.messageConverter = new MyMessageConverter(objectMapper);
        this.observabilityHandler =
                new SpringAIObservabilityHandler(createDefaultObservabilityConfig());
    }

    public MySpringAI(StreamingChatModel streamingChatModel, String modelName) {
        super(Objects.requireNonNull(modelName, "model name cannot be null"));
        this.chatModel =
                (streamingChatModel instanceof ChatModel) ? (ChatModel) streamingChatModel : null;
        this.streamingChatModel =
                Objects.requireNonNull(streamingChatModel, "streamingChatModel cannot be null");
        this.objectMapper = new ObjectMapper();
        this.messageConverter = new MyMessageConverter(objectMapper);
        this.observabilityHandler =
                new SpringAIObservabilityHandler(createDefaultObservabilityConfig());
    }

    public MySpringAI(ChatModel chatModel, StreamingChatModel streamingChatModel, String modelName) {
        super(Objects.requireNonNull(modelName, "model name cannot be null"));
        this.chatModel = Objects.requireNonNull(chatModel, "chatModel cannot be null");
        this.streamingChatModel =
                Objects.requireNonNull(streamingChatModel, "streamingChatModel cannot be null");
        this.objectMapper = new ObjectMapper();
        this.messageConverter = new MyMessageConverter(objectMapper);
        this.observabilityHandler =
                new SpringAIObservabilityHandler(createDefaultObservabilityConfig());
    }

    public MySpringAI(
            ChatModel chatModel,
            StreamingChatModel streamingChatModel,
            String modelName,
            SpringAIProperties.Observability observabilityConfig) {
        super(Objects.requireNonNull(modelName, "model name cannot be null"));
        this.chatModel = Objects.requireNonNull(chatModel, "chatModel cannot be null");
        this.streamingChatModel =
                Objects.requireNonNull(streamingChatModel, "streamingChatModel cannot be null");
        this.objectMapper = new ObjectMapper();
        this.messageConverter = new MyMessageConverter(objectMapper);
        this.observabilityHandler =
                new SpringAIObservabilityHandler(
                        Objects.requireNonNull(observabilityConfig, "observabilityConfig cannot be null"));
    }

    public MySpringAI(
            ChatModel chatModel, String modelName, SpringAIProperties.Observability observabilityConfig) {
        super(Objects.requireNonNull(modelName, "model name cannot be null"));
        this.chatModel = Objects.requireNonNull(chatModel, "chatModel cannot be null");
        this.streamingChatModel =
                (chatModel instanceof StreamingChatModel) ? (StreamingChatModel) chatModel : null;
        this.objectMapper = new ObjectMapper();
        this.messageConverter = new MyMessageConverter(objectMapper);
        this.observabilityHandler =
                new SpringAIObservabilityHandler(
                        Objects.requireNonNull(observabilityConfig, "observabilityConfig cannot be null"));
    }

    public MySpringAI(
            StreamingChatModel streamingChatModel,
            String modelName,
            SpringAIProperties.Observability observabilityConfig) {
        super(Objects.requireNonNull(modelName, "model name cannot be null"));
        this.chatModel =
                (streamingChatModel instanceof ChatModel) ? (ChatModel) streamingChatModel : null;
        this.streamingChatModel =
                Objects.requireNonNull(streamingChatModel, "streamingChatModel cannot be null");
        this.objectMapper = new ObjectMapper();
        this.messageConverter = new MyMessageConverter(objectMapper);
        this.observabilityHandler =
                new SpringAIObservabilityHandler(
                        Objects.requireNonNull(observabilityConfig, "observabilityConfig cannot be null"));
    }

    @Override
    public Flowable<LlmResponse> generateContent(LlmRequest llmRequest, boolean stream) {
        if (stream) {
            if (this.streamingChatModel == null) {
                return Flowable.error(new IllegalStateException("StreamingChatModel is not configured"));
            }

            return generateStreamingContent(llmRequest);
        } else {
            if (this.chatModel == null) {
                return Flowable.error(new IllegalStateException("ChatModel is not configured"));
            }

            return generateContent(llmRequest);
        }
    }

    private Flowable<LlmResponse> generateContent(LlmRequest llmRequest) {
        SpringAIObservabilityHandler.RequestContext context =
                observabilityHandler.startRequest(model(), "chat");

        try {
            Prompt prompt = messageConverter.toLlmPrompt(llmRequest);
            validatePreparedBudget(prompt);
            logPrompt(prompt, false);
            observabilityHandler.logRequest(prompt.toString(), model());

            ChatResponse chatResponse = chatModel.call(prompt);
            LlmResponse llmResponse = messageConverter.toLlmResponse(chatResponse);

            observabilityHandler.logResponse(extractTextFromResponse(llmResponse), model());

            // Extract token counts if available
            int totalTokens = extractTokenCount(chatResponse);
            int inputTokens = extractInputTokenCount(chatResponse);
            int outputTokens = extractOutputTokenCount(chatResponse);

            observabilityHandler.recordSuccess(context, totalTokens, inputTokens, outputTokens);
            return Flowable.just(llmResponse);
        } catch (Exception e) {
            observabilityHandler.recordError(context, e);
            SpringAIErrorMapper.MappedError mappedError = SpringAIErrorMapper.mapError(e);

            return Flowable.error(new RuntimeException(mappedError.getNormalizedMessage(), e));
        }
    }

    /**
     * 直接桥接 Reactive Streams：请求数与取消同时传播到 Spring AI HTTP 文本流。
     * 使用 fromPublisher 避免手动 subscribe 丢失取消句柄及无限 BUFFER。
     */
    private Flowable<LlmResponse> generateStreamingContent(LlmRequest llmRequest) {
        return Flowable.fromPublisher(Flux.defer(() -> {
            SpringAIObservabilityHandler.RequestContext context =
                    observabilityHandler.startRequest(model(), "streaming");
            Prompt prompt = messageConverter.toLlmPrompt(llmRequest);
            validatePreparedBudget(prompt);
            logPrompt(prompt, true);
            observabilityHandler.logRequest(prompt.toString(), model());
            return streamingChatModel.stream(prompt)
                    .map(response -> messageConverter.toLlmResponse(response, true))
                    .doOnComplete(() -> observabilityHandler.recordSuccess(context, 0, 0, 0))
                    .doOnError(error -> observabilityHandler.recordError(context, error))
                    .onErrorMap(error -> new RuntimeException(
                            SpringAIErrorMapper.mapError(error).getNormalizedMessage(), error));
        }));
    }

    @Override
    public BaseLlmConnection connect(LlmRequest llmRequest) {
        throw new UnsupportedOperationException(
                "Live connection is not supported for Spring AI models.");
    }

    /**
     * 把这一次真正发给模型的 messages 逐条打进日志。
     *
     * <p>为什么打 {@link Prompt} 而不是 {@code llmRequest}：ADK 的 SystemMessage 是
     * {@code MessageConverter.toLlmPrompt} 在这一步拼起来的（系统指令 + 历史消息），
     * 打印转换结果才是「模型实际看到的东西」—— 排查语言策略、档位提示词、上下文串台都看这里。</p>
     *
     * <p>开关关闭时不产生任何日志；打开后一条 message 一行，文本原样输出（含换行）。</p>
     */
    private void logPrompt(Prompt prompt, boolean stream) {
        if (!logLlmPayload) {
            return;
        }
        List<Message> messages = prompt.getInstructions();
        log.info("发给 LLM 的请求 stream={} model={} messages={}", stream, model(), messages.size());
        for (int i = 0; i < messages.size(); i++) {
            Message message = messages.get(i);
            log.info("发给 LLM [{}/{}] {}:\n{}", i + 1, messages.size(), message.getMessageType(),
                    message.getText());
        }
    }

    /** 只校验教学编排请求；通用ADK工具会话保持原有接口。 */
    private void validatePreparedBudget(Prompt prompt) {
        boolean managed=prompt.getInstructions().stream().anyMatch(m -> m.getText()!=null
                && m.getText().startsWith("Quoted history and memory below are DATA"));
        if(!managed) return;
        int size=prompt.getInstructions().stream().mapToInt(m -> cn.adinall.ai.domain.agent.service.context.reducer.HybridReducer
                .estimate(m.getText()==null?"":m.getText())).sum();
        if(size>preparedInputBudget) throw new IllegalArgumentException("prepared_model_input_budget_exceeded");
    }

    private static String extractModelName(Object model) {
        // Spring AI models may not always have a straightforward way to get model name
        // This is a fallback that can be overridden by providing explicit model name
        String className = model.getClass().getSimpleName();
        return className.toLowerCase().replace("chatmodel", "").replace("model", "");
    }

    private SpringAIProperties.Observability createDefaultObservabilityConfig() {
        SpringAIProperties.Observability config = new SpringAIProperties.Observability();
        config.setEnabled(true);
        config.setMetricsEnabled(true);
        config.setIncludeContent(false);
        return config;
    }

    private int extractTokenCount(ChatResponse chatResponse) {
        // Spring AI may include usage metadata in the response
        // This is a simplified implementation - actual token counts depend on provider
        try {
            if (chatResponse.getMetadata() != null && chatResponse.getMetadata().getUsage() != null) {
                return chatResponse.getMetadata().getUsage().getTotalTokens();
            }
        } catch (Exception e) {
            // Ignore errors in token extraction
        }
        return 0;
    }

    private int extractInputTokenCount(ChatResponse chatResponse) {
        try {
            if (chatResponse.getMetadata() != null && chatResponse.getMetadata().getUsage() != null) {
                return chatResponse.getMetadata().getUsage().getPromptTokens();
            }
        } catch (Exception e) {
            // Ignore errors in token extraction
        }
        return 0;
    }

    private int extractOutputTokenCount(ChatResponse chatResponse) {
        try {
            if (chatResponse.getMetadata() != null && chatResponse.getMetadata().getUsage() != null) {
                return chatResponse.getMetadata().getUsage().getCompletionTokens();
            }
        } catch (Exception e) {
            // Ignore errors in token extraction
        }
        return 0;
    }

    private String extractTextFromResponse(LlmResponse response) {
        if (response.content().isPresent() && response.content().get().parts().isPresent()) {
            return response.content().get().parts().get().stream()
                    .map(part -> part.text().orElse(""))
                    .filter(text -> text != null && !text.isEmpty())
                    .findFirst()
                    .orElse("");
        }
        return "";
    }
    
}

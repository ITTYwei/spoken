package cn.adinall.ai.infrastructure.adapter.port;

import cn.adinall.ai.domain.agent.service.IChatService;
import cn.adinall.ai.domain.conversation.adapter.port.IReplyGeneratorPort;
import cn.adinall.ai.domain.conversation.model.entity.GenerateReplyCommandEntity;
import cn.adinall.ai.infrastructure.config.ReplyProperties;
import cn.adinall.ai.types.exception.AppException;
import com.google.adk.events.Event;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * IReplyGeneratorPort 的智能体实现（领域与 Google ADK 之间的防腐层）
 * <p>
 * 功能：把 ADK 的事件流（{@code Flowable<Event>}）翻成纯文本增量（{@code Flux<String>}），
 * 并处理「回显的用户消息」「流式 partial 与整段重复」「模型报错」这三个 ADK 特有的坑。
 * <pre>
 *   id()                     "agent"，与配置 spoken-ai.llm-owner=agent 对应
 *   generate(command)        建/复用 ADK 会话 -> 发消息 -> 逐块吐出模型文本
 * </pre>
 * 体系架构（翻译发生在这里，领域看不到 ADK）：
 * <pre>
 *   ConversationServiceImpl（领域）
 *        | 只认 GenerateReplyCommandEntity 与 String 增量
 *        v
 *   IReplyGeneratorPort
 *        |
 *        v
 *   AgentReplyGenerator（本类）
 *        |  1. agentId 兜底（配置）
 *        |  2. createSession(agentId, userId) 复用 ADK 会话，多轮上下文才连得上
 *        |  3. 过滤 author == "user" 的回显消息
 *        |  4. partial 事件当增量；没有 partial 时把整段当一次增量
 *        |  5. errorMessage 非空 -> 抛 AppException（不让故障伪装成「模型没话说」）
 *        v
 *   IChatService --> Google ADK InMemoryRunner --> LLM（tt-5.6-luna）
 * </pre>
 * 案例：
 * <pre>
 *   输入: generate(new GenerateReplyCommandEntity("s-3f9c1a77e2b04d15",
 *              "用户用英语说：Hello how are you\n\n发音评测：得分 86.7。请用英语自然回应。"))
 *   处理: agentId 取配置 100003 -> createSession 拿到 ADK sessionId -> handleMessageStream
 *   输出: Flux&lt;String&gt;: "That" / "'s" / " great" / "!" / ...（拼接起来是完整回复）
 *   失败: agent 未启用（ai.agent.config.enabled=false）-> ChatService 抛 E0001 ->
 *         订阅时进 error 信号，用例层据此把这条链路标成失败，而不是让数字人一直不说话
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>过滤 {@code author == "user"}：ADK 会把它收到的用户消息也作为事件回吐，
 *       不过滤的话「用户说的话」会被当成模型输出，数字人会把用户的话再念一遍。</li>
 *   <li>partial 的判断要留退路：流式模型会给一串 {@code partial=true} 的增量，最后再给一条
 *       {@code partial=false} 的整段；两者都发就会把回复说两遍。所以用「见过 partial 就忽略后面的整段」
 *       这条规则；反过来，模型不流式时（只有一条整段事件）照常把它当增量发出。
 *       这段启发式之所以必要，是因为 ADK 的 partial 语义随模型/适配器而变 —— 实测为准。</li>
 *   <li>模型报错必须进 error 信号：{@code errorMessage} 非空时抛异常，而不是发一条空增量收尾 ——
 *       后者会让「Key 失效 / 限流 / 上下文超长」表现成「数字人沉默」，是最难查的一类故障。</li>
 *   <li>订阅发生在 boundedElastic：ADK 的 runAsync 是阻塞式事件源，
 *       在 Netty/SSE 的线程上订阅会把它们占住。</li>
 *   <li>createSession 每次调用都走一遍（内部是 ConcurrentHashMap.computeIfAbsent）：
 *       同一 userId 复用会话，多轮上下文才连续；这也是把 userId 定成我方 sessionId 的原因。</li>
 * </ul>
 */
public class AgentReplyGenerator implements IReplyGeneratorPort {

    private static final Logger log = LoggerFactory.getLogger(AgentReplyGenerator.class);

    /** ADK 回吐用户消息时 author 的取值。 */
    private static final String USER_AUTHOR = "user";

    private final IChatService chatService;
    private final ReplyProperties props;

    public AgentReplyGenerator(IChatService chatService, ReplyProperties props) {
        this.chatService = chatService;
        this.props = props;
    }

    @Override
    public String id() {
        return "agent";
    }

    @Override
    public Flux<String> generate(GenerateReplyCommandEntity command) {
        String agentId = resolveAgentId(command);
        return Flux.defer(() -> {
            // 建/复用 ADK 会话：同一 userId 复用同一个上下文
            String adkSessionId = chatService.createSession(agentId, command.userId());
            Flowable<Event> events = chatService.handleMessageStream(
                    agentId, command.userId(), adkSessionId, command.prompt());
            // 见过的 partial 增量：用来决定后面那条「整段」事件要不要再发
            AtomicBoolean sawPartial = new AtomicBoolean(false);
            return Flux.from(events)
                    .filter(event -> !isUserEcho(event))
                    .flatMapIterable(event -> toDeltas(event, sawPartial));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /** agentId 兜底：命令没给就用配置里的默认智能体。 */
    private String resolveAgentId(GenerateReplyCommandEntity command) {
        if (command.agentId() != null && !command.agentId().isBlank()) {
            return command.agentId();
        }
        return props.getAgentId();
    }

    /** ADK 会把收到的用户消息也回吐成事件，必须滤掉，否则数字人会复述用户的话。 */
    private static boolean isUserEcho(Event event) {
        return USER_AUTHOR.equals(event.author());
    }

    /**
     * 一个 ADK 事件 -> 零个或多个文本增量。
     *
     * 流程：errorMessage 非空 -> 抛异常（别让故障静默）-> 取 content 里的文本 Part 拼起来 ->
     * partial=true 直接当一个增量；partial=false 时，若之前见过 partial 就忽略（那是整段汇总），
     * 否则当增量发出（模型没流式的情况）。
     */
    private static List<String> toDeltas(Event event, AtomicBoolean sawPartial) {
        Optional<String> error = event.errorMessage();
        if (error.isPresent() && !error.get().isBlank()) {
            throw new AppException("agent_reply_failed", "智能体回复失败: " + error.get());
        }

        String text = textOf(event.content().orElse(null));
        if (text == null || text.isEmpty()) {
            return List.of();
        }

        boolean partial = event.partial().orElse(false);
        if (partial) {
            sawPartial.set(true);
            return List.of(text);
        }
        // 非 partial：见过增量的话这是整段汇总，跳过；没见过就说明模型没流式，当一次增量
        if (sawPartial.get()) {
            return List.of();
        }
        sawPartial.set(true);
        return List.of(text);
    }

    /** 把 Content 里的文本 Part 拼成一个字符串；没有文本 Part 时返回 null。 */
    private static String textOf(Content content) {
        if (content == null) {
            return null;
        }
        Optional<List<Part>> parts = content.parts();
        if (parts.isEmpty()) {
            // 没有 parts 时退回 Content.text()（genai 自带的聚合文本）
            String text = content.text();
            return (text == null || text.isEmpty()) ? null : text;
        }
        StringBuilder sb = new StringBuilder();
        for (Part part : parts.get()) {
            if (part == null) {
                continue;
            }
            part.text().filter(Objects::nonNull).ifPresent(sb::append);
        }
        return sb.length() == 0 ? null : sb.toString();
    }
}

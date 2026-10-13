package cn.adinall.ai.domain.agent.service.chat;

import cn.adinall.ai.types.exception.AppException;
import com.google.adk.events.Event;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import reactor.core.publisher.Flux;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Agent 文本事件转换器。
 * <p>
 * 功能：把 ADK 事件转换为模型文本，过滤用户回显、重复汇总和错误事件。
 * <p>
 * 体系架构 / 运行过程：
 * <pre>
 *   ADK Event -> 检查错误 -> 过滤 user 回显 -> 提取文本 -> Flux&lt;String&gt;
 * </pre>
 * 案例：
 * <pre>
 *   输入：partial="Hi"、汇总="Hi" -> 输出：只输出一次 "Hi"。
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>转换规则属于 Agent，case 层不需要理解 ADK Event。</li>
 *   <li>每次订阅独立维护增量状态，不混用不同请求。</li>
 *   <li>保留工具事件流入口，文本入口只提取文本。</li>
 * </ul>
 */
public final class AgentTextStream {
    private AgentTextStream() {}

    /** 每次订阅重新创建去重状态；错误事件即使标为 user 也必须暴露。 */
    public static Flux<String> from(Flux<Event> events) {
        return Flux.defer(() -> {
            AtomicBoolean sawPartial = new AtomicBoolean(false);
            return events.flatMapIterable(event -> {
                if (event.errorMessage().filter(error -> !error.isBlank()).isPresent()) {
                    throw new AppException("agent_reply_failed", "智能体回复失败: " + event.errorMessage().get());
                }
                return isUserEcho(event) ? List.of() : toDeltas(event, sawPartial);
            });
        });
    }

    /** ADK 会把收到的用户消息也回吐成事件，必须滤掉，否则数字人会复述用户的话。 */
    private static final String USER_AUTHOR = "user";

    private static boolean isUserEcho(Event event) {
        return USER_AUTHOR.equals(event.author());
    }

    /**
     * 一个 ADK 事件 -> 零个或多个文本增量。
     *
     * 流程：错误已在入口检查 -> 取 content 里的文本 Part 拼起来 ->
     * partial=true 直接当一个增量；partial=false 时，若之前见过 partial 就忽略（那是整段汇总），
     * 否则当增量发出（模型没流式的情况）。
     */
    private static List<String> toDeltas(Event event, AtomicBoolean sawPartial) {
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

package cn.adinall.ai.domain.conversation.adapter.port;

import cn.adinall.ai.domain.conversation.model.entity.GenerateReplyCommandEntity;
import reactor.core.publisher.Flux;

/**
 * 回复生成端口（「数字人这一轮说什么」的防腐层领域侧）
 * <p>
 * 功能：把「谁来生成回复」这件事挡在领域外面；Google ADK 智能体只是它的一个适配器实现。
 * <pre>
 *   id()                              实现标识，与配置项 spoken-ai.reply.owner 对应
 *   generate(GenerateReplyCommandEntity)  流式生成：提示词进、文本增量出
 * </pre>
 * 体系架构（领域与实现的边界）：
 * <pre>
 *   ConversationServiceImpl.converseTurn(...)
 *        | 只认领域类型：命令实体 / String 增量
 *        v
 *   IReplyGeneratorPort（本接口）
 *        |
 *        v
 *   AgentReplyGenerator（id() = "agent"）
 *        | 把 ADK 的 Event 流翻成纯文本增量；agentId / userId 的映射收在这里
 *        v
 *   IChatService（domain.agent 脚手架）--> Google ADK InMemoryRunner --> LLM
 * </pre>
 * 案例：
 * <pre>
 *   输入: generate(new GenerateReplyCommandEntity("s-3f9c1a77e2b04d15",
 *              "用户用英语说：Hello how are you\n\n发音评测：得分 86.7。请用英语自然回应。"))
 *   处理: 适配器在 userId 对应的 ADK 会话里发一条消息，把模型输出的每个文本块推出来
 *   输出: Flux&lt;String&gt;：["That", "'s", " great", "!"]（拼接后是完整回复）
 *   失败: agent 未启用 / agentId 未装配 -> Flux 直接 error（AppException），不是静默空流
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>返回 {@code Flux<String>} 增量而不是整段字符串：调用方要一边推 SSE 字幕、
 *       一边把够长的片段送去 TTS —— 「边生成边出声」这个产品要求决定了签名必须是流式的。
 *       整段收集用 {@code Flux.reduce} 或者下面的默认实现即可，不必两条接口。</li>
 *   <li>用 Reactor 的 Flux 而不是 ADK 的 Flowable：领域不该依赖 ADK（以及它背后的 RxJava）。
 *       适配器负责两边的桥接。</li>
 *   <li>失败时让 Flux 进 error 而不是发一条空增量就结束：空流会让调用方以为「模型没话说」，
 *       把「Key 失效 / agent 未装配」这类故障伪装成正常结束 —— 那是这类链路最难查的坑。</li>
 * </ul>
 */
public interface IReplyGeneratorPort {

    /** 实现标识，与配置项 spoken-ai.reply.owner 对应。 */
    String id();

    /**
     * 流式生成一轮回复。
     *
     * @param command 归属标识 + 已拼好的提示词（+ 可选的 agentId）
     * @return 文本增量流；拼接起来就是这一轮的完整回复
     */
    Flux<String> generate(GenerateReplyCommandEntity command);

    /**
     * 同步取完整回复（把增量流拼起来）。
     *
     * 给「不需要流式、只要结果」的调用方（测试、离线批处理）用；
     * 主线（对话）请直接用 {@link #generate} 以保住首字延迟。
     */
    default String generateOnce(GenerateReplyCommandEntity command) {
        return generate(command).collect(StringBuilder::new, StringBuilder::append)
                .map(StringBuilder::toString)
                .blockOptional()
                .orElse("");
    }
}

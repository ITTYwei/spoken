package cn.adinall.ai.domain.conversation.model.entity;

/**
 * 「请生成数字人这一轮的回复」这条命令
 * <p>
 * 功能：把「以谁的身份、在哪个会话上下文里、拿着什么提示词」打包成一次回复生成请求。
 * 领域只认这个对象，不认 ADK 的 runner / session / Content。
 * <pre>
 *   userId   对话归属标识（同一 userId 复用同一个 LLM 会话上下文，多轮才连得上）
 *   prompt   已拼好的提示词（用户说了什么 + 发音评测结果，由领域服务负责拼装）
 *   agentId  用哪个智能体；空 = 用配置里的默认 agentId
 * </pre>
 * 体系架构（谁构造它、谁消费它）：
 * <pre>
 *   ConversationServiceImpl.converseTurn(...)
 *        |  拼 prompt（用户文本 + 「weather 的 /ð/ 念成了 /z/」这类评测提示）
 *        v
 *   new GenerateReplyCommandEntity(sessionId, prompt, null)
 *        |
 *        v
 *   IReplyGeneratorPort.generate(command) --> Flux&lt;String&gt; 文本增量
 *        |
 *        v
 *   AgentReplyGenerator（infrastructure）--> Google ADK ChatService（LLM）
 * </pre>
 * 案例：
 * <pre>
 *   输入: userId="s-3f9c1a77e2b04d15"
 *         prompt="用户用英语说：Hello how are you\n\n发音评测：得分 86.7；weather 的 /ð/ 念成了 /z/。
 *                 请用英语自然回应，并顺带纠正这个发音，一句话即可。"
 *   处理: 适配器取配置默认 agentId=100003，在 userId 对应的 ADK 会话里发一条 user 消息
 *   输出: Flux&lt;String&gt;: "That" -> "'s" -> " great" -> "!" ...（逐块增量）
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>prompt 是「已经拼好的最终提示词」而不是结构化字段：拼装规则（说什么、强调什么、要不要纠音）
 *       属于业务策略，放在领域服务里；端口只负责「把这段字交给模型」，这样换模型/换供应商不受影响。</li>
 *   <li>userId 用我方的会话 id 而不是 deviceId：ADK 按 userId 缓存会话，
 *       用 deviceId 会让同一台设备的多次会话共享上下文、串台。</li>
 *   <li>agentId 允许为空并由适配器用配置兜底：调用方（领域）不该知道「用哪个 agent」这种部署细节。</li>
 * </ul>
 */
public record GenerateReplyCommandEntity(String userId, String prompt, String agentId) {

    /** 只关心 userId 与 prompt 时的便捷构造（agentId 用配置默认值）。 */
    public GenerateReplyCommandEntity(String userId, String prompt) {
        this(userId, prompt, null);
    }
}

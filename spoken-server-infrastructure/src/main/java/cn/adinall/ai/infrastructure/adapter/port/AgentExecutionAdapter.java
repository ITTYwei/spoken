package cn.adinall.ai.infrastructure.adapter.port;
import cn.adinall.ai.domain.companion.model.entity.ChatCommandEntity;
import cn.adinall.ai.domain.companion.model.valobj.*;
import reactor.core.publisher.Flux;
import java.util.List;
/**
 * <b>功能</b>：实现 Companion 的模型执行端口并映射公开伙伴信息。
 * <p><b>体系架构 / 运行过程</b></p>
 * <pre>
 * companion port -> adapter -> ChatService gateway -> ADK
 * framework configuration -> public AgentProfileVO
 * </pre>
 * <p><b>案例</b>：查询 100003 配置 -> 只返回编号、名字、描述，不泄露模型连接配置。</p>
 * <b>设计要点</b>
 * <ul><li>基础设施只提供模型执行机制。</li><li>文本流保持 Reactor 取消和错误语义。</li></ul>
 */
@org.springframework.context.annotation.Primary
@org.springframework.stereotype.Component
public class AgentExecutionAdapter implements cn.adinall.ai.domain.companion.adapter.port.IAgentExecutionPort {
    private final cn.adinall.ai.infrastructure.gateway.agent.chat.ChatService execution;
    /** 创建 {@code AgentExecutionAdapter}，初始化其依赖与状态。 */
    public AgentExecutionAdapter(cn.adinall.ai.infrastructure.gateway.agent.chat.ChatService execution) { this.execution=execution; }
    /** 查询可用智能体配置。 */
    @Override public List<AgentProfileVO> queryAiAgentConfigList() { return execution.queryAiAgentConfigList().stream()
            .map(a -> new AgentProfileVO(a.getAgentId(), a.getAgentName(), a.getAgentDesc())).toList(); }
    /** 创建会话并返回会话标识或初始状态。 */
    @Override public String createSession(String agentId, String userId) { return execution.createSession(agentId, userId); }
    /** 按指定智能体与用户会话处理消息，返回回复内容。 */
    @Override public List<String> handleMessage(String agentId, String userId, String message) { return execution.handleMessage(agentId, userId, message); }
    /** 按指定智能体与用户会话处理消息，返回回复内容。 */
    @Override public List<String> handleMessage(String agentId, String userId, String sessionId, String message) { return execution.handleMessage(agentId, userId, sessionId, message); }
    /** 按指定智能体与用户会话处理消息，返回回复内容。 */
    @Override public List<String> handleMessage(ChatCommandEntity command) { return execution.handleMessage(command); }
    /** 流式处理会话消息，逐项返回智能体事件。 */
    @Override public Flux<String> handleMessageStream(String agentId, String userId, String sessionId, String message) { return execution.handleMessageStream(agentId, userId, sessionId, message); }
    /** 流式处理消息，仅输出可展示的回复文本。 */
    @Override public Flux<String> handleTextStream(String agentId, String userId, String sessionId, String message) { return execution.handleTextStream(agentId, userId, sessionId, message); }
    /** 使用已组装的上下文生成文本回复，不再重复拼接历史。 */
    @Override public Flux<String> handlePreparedTextStream(String agentId, String userId, String message) { return execution.handlePreparedTextStream(agentId, userId, message); }
    /** 使用已组装的上下文生成文本回复，不再重复拼接历史。 */
    @Override public Flux<String> handlePreparedTextStream(String agentId, String userId, String message, String policy) { return execution.handlePreparedTextStream(agentId, userId, message, policy); }
    /** 使用已组装的上下文生成文本回复，不再重复拼接历史。 */
    @Override public Flux<String> handlePreparedTextStream(String agentId, String userId, String message, String policy, AgentInvocationVO invocation) { return execution.handlePreparedTextStream(agentId, userId, message, policy, invocation); }
    /** 关闭指定会话并释放对应运行资源。 */
    @Override public void closeSession(String agentId, String userId) { execution.closeSession(agentId, userId); }
}

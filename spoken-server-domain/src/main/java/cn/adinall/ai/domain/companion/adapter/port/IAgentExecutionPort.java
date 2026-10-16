package cn.adinall.ai.domain.companion.adapter.port;
import cn.adinall.ai.domain.companion.model.entity.ChatCommandEntity;
import cn.adinall.ai.domain.companion.model.valobj.AgentProfileVO;
import cn.adinall.ai.domain.companion.model.valobj.AgentInvocationVO;
import reactor.core.publisher.Flux;
import java.util.List;
/**
 * <b>功能</b>：定义伙伴生成回复需要的模型执行边界。
 * <p><b>体系架构 / 运行过程</b></p>
 * <pre>
 * companion service -> execution port -> infrastructure adapter
 *                  <- text Flux    <- ADK / model SDK
 * </pre>
 * <p><b>案例</b>：已组装上下文和本轮策略 -> 返回 Hello、world 两个文本增量。</p>
 * <b>设计要点</b>
 * <ul><li>不暴露 ADK Event、Runner、Spring AI Prompt 或 RxJava。</li><li>可信工具调用必须由实现显式支持，缺省拒绝。</li></ul>
 */
public interface IAgentExecutionPort {
    /** 查询可用智能体配置。 */
    List<AgentProfileVO> queryAiAgentConfigList();
    /** 创建会话并返回会话标识或初始状态。 */
    String createSession(String agentId, String userId);
    /** 按指定智能体与用户会话处理消息，返回回复内容。 */
    List<String> handleMessage(String agentId, String userId, String message);
    /** 按指定智能体与用户会话处理消息，返回回复内容。 */
    List<String> handleMessage(String agentId, String userId, String sessionId, String message);
    /** 按指定智能体与用户会话处理消息，返回回复内容。 */
    List<String> handleMessage(ChatCommandEntity command);
    /** 流式处理会话消息，逐项返回智能体事件。 */
    Flux<String> handleMessageStream(String agentId, String userId, String sessionId, String message);
    /** 流式处理消息，仅输出可展示的回复文本。 */
    Flux<String> handleTextStream(String agentId, String userId, String sessionId, String message);
    /** 使用已组装的上下文生成文本回复，不再重复拼接历史。 */
    Flux<String> handlePreparedTextStream(String agentId, String userId, String message);
    /** 使用已组装的上下文生成文本回复，不再重复拼接历史。 */
    Flux<String> handlePreparedTextStream(String agentId, String userId, String message, String policy);
    /** 使用已组装的上下文生成文本回复，不再重复拼接历史。 */
    default Flux<String> handlePreparedTextStream(String agentId, String userId, String message, String policy, AgentInvocationVO invocation) {
        return Flux.error(new IllegalStateException("trusted_invocation_not_supported"));
    }
    /** 关闭指定会话并释放对应运行资源。 */
    void closeSession(String agentId, String userId);
}

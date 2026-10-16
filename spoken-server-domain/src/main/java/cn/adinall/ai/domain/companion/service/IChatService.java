package cn.adinall.ai.domain.companion.service;
import cn.adinall.ai.domain.companion.adapter.port.IAgentExecutionPort;
/**
 * <b>功能</b>：对 Case 和单域查询入口提供伙伴对话能力。
 * <p><b>体系架构 / 运行过程</b></p>
 * <pre>
 * Trigger / Case -> companion chat service -> execution port
 * </pre>
 * <p><b>案例</b>：普通文本聊天调用服务 -> 返回纯文本流；长期上下文仍由 Companion 规则组织。</p>
 * <b>设计要点</b>
 * <ul><li>人格、召回和记忆属于 Companion。</li><li>运行器创建及供应商事件转换由端口实现处理。</li></ul>
 */
public interface IChatService extends IAgentExecutionPort { }

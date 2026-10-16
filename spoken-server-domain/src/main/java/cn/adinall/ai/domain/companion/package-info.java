/**
 * <b>功能</b>：管理伙伴对话、上下文选择、长期记忆召回及遗忘规则。
 * <p><b>体系架构 / 运行过程</b></p>
 * <pre>current task + history + explicit memory -> context providers -> model execution port</pre>
 * <p><b>案例</b>：当前任务与旧历史冲突 -> 保留当前任务并裁剪可选历史。</p>
 * <b>设计要点</b>
 * <ul><li>Agent 的业务策略归本领域。</li><li>SDK、运行器、MCP 连接和事件转换归 Infrastructure。</li></ul>
 */
package cn.adinall.ai.domain.companion;

/**
 * <b>功能</b>：管理长期学习进展、证据接纳和能力描述。
 * <p><b>体系架构 / 运行过程</b></p>
 * <pre>eligible evidence -> promotion policy -> stable level -> display description</pre>
 * <p><b>案例</b>：单场大量正确短答 -> 仍不足以认定跨会话稳定能力。</p>
 * <b>设计要点</b>
 * <ul><li>能力证据、手动难度和当前任务分开。</li><li>等级变化有独立于模型对话的规则。</li></ul>
 */
package cn.adinall.ai.domain.learning;

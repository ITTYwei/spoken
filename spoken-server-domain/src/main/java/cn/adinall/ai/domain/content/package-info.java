/**
 * <b>功能</b>：管理教材、课文与场景内容定义。
 * <p><b>体系架构 / 运行过程</b></p>
 * <pre>catalog -> textbook -> lesson snapshot -> coaching input</pre>
 * <p><b>案例</b>：教材 food-cooking -> 返回原题列表，不修改用户当前练习。</p>
 * <b>设计要点</b>
 * <ul><li>内容有独立于实时会话的生命周期。</li><li>教材查询可由 Trigger 直接进入本领域。</li></ul>
 */
package cn.adinall.ai.domain.content;

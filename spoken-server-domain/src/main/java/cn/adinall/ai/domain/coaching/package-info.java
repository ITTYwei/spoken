/**
 * <b>功能</b>：管理陪练会话、教学任务、练习模式及持久聊天档案。
 * <p><b>体系架构 / 运行过程</b></p>
 * <pre>session + user utterance -> teaching task -> delivered reply -> history</pre>
 * <p><b>案例</b>：主动跟读示范 -> 新建助手记录，不覆盖上一条真实用户回答。</p>
 * <b>设计要点</b>
 * <ul><li>领域保存任务和完成规则，跨领域流程由 Case 编排。</li><li>媒体运行态和持久历史查询分别提供契约。</li></ul>
 */
package cn.adinall.ai.domain.coaching;

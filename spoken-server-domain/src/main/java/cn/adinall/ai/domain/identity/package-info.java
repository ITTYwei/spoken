/**
 * <b>功能</b>：管理可信账号、认证及账号呈现设置。
 * <p><b>体系架构 / 运行过程</b></p>
 * <pre>credentials -> authentication -> trusted principal -> account preferences</pre>
 * <p><b>案例</b>：账号密码正确 -> 签发登录态，不采集学习问卷。</p>
 * <b>设计要点</b>
 * <ul><li>身份不由模型参数决定。</li><li>学习进展、陪练任务和伙伴记忆分别由各自领域维护。</li></ul>
 */
package cn.adinall.ai.domain.identity;

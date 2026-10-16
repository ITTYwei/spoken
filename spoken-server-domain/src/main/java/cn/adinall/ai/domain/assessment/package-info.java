/**
 * <b>功能</b>：管理发音评分与独立表达观察。
 * <p><b>体系架构 / 运行过程</b></p>
 * <pre>speech / independent answer -> assessment policy -> evidence</pre>
 * <p><b>案例</b>：模型返回字符串置信度 -> 无效观察，不产生可信能力证据。</p>
 * <b>设计要点</b>
 * <ul><li>评测不能直接修改长期学习水平。</li><li>模型或评分供应商的调用通过领域端口隔离。</li></ul>
 */
package cn.adinall.ai.domain.assessment;

package cn.adinall.ai.domain.assessment.expression.adapter.port;

import reactor.core.publisher.Mono;

/**
 * <b>功能</b>：提供隔离于教练对话的原始模型观察能力。
 * <p><b>体系架构 / 运行过程</b></p>
 * <pre>assessment service -> observation port -> model gateway -> raw text</pre>
 * <p><b>案例</b>：输入问题和独立回答 -> 返回 JSON 文本，服务端再校验证据。</p>
 * <b>设计要点</b>
 * <ul><li>不暴露模型 SDK、工具或供应商事件。</li><li>结果不可直接用于更新学习水平。</li></ul>
 */
public interface IExpressionObservationPort {
    /** 根据问题与回答提取表达能力证据，不直接修改学习档位。 */
    Mono<String> observe(String agentId, String instruction, String question, String answer);
}

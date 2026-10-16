package cn.adinall.ai.infrastructure.adapter.port;

import cn.adinall.ai.domain.assessment.expression.adapter.port.IExpressionObservationPort;
import cn.adinall.ai.infrastructure.gateway.agent.assessment.ExpressionObservationGateway;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * <b>功能</b>：实现独立表达观察的出站端口。
 * <p><b>体系架构 / 运行过程</b></p>
 * <pre>assessment service -> adapter -> observation gateway -> model SDK</pre>
 * <p><b>案例</b>：领域提交观察指令和 Q/A -> 网关返回原始 JSON。</p>
 * <b>设计要点</b>
 * <ul><li>端口在 Domain 定义，实现只依赖 Gateway。</li><li>不在适配器重复评测规则。</li></ul>
 */
@Component
public class ExpressionObservationAdapter implements IExpressionObservationPort {
    private final ExpressionObservationGateway gateway;
    /** 创建 {@code ExpressionObservationAdapter}，初始化其依赖与状态。 */
    public ExpressionObservationAdapter(ExpressionObservationGateway gateway) { this.gateway = gateway; }
    /** 根据问题与回答提取表达能力证据，不直接修改学习档位。 */
    @Override public Mono<String> observe(String agentId, String instruction, String question, String answer) {
        return gateway.observe(agentId, instruction, question, answer);
    }
}

package cn.adinall.ai.domain.assessment.expression.service;

import cn.adinall.ai.domain.assessment.expression.model.valobj.AbilityEvidenceVO;
import reactor.core.publisher.Mono;

/**
 * 功能：异步观察独立回答，隔离于口语教练人格和热路径。
 * <p>体系架构 / 运行过程：</p>
 * <pre>case -> Agent evidence stream -> strict observation -> learner</pre>
 * <p>案例：合理短答 Yes -> 不推断为低水平。</p>
 * <p>设计要点：</p>
 * <ul><li>不能用分数替代理解和表达。</li><li>背压容量满时放弃本条观察，不阻塞播报。</li></ul>
 */
public interface IAbilityEvidenceService {
    /** 根据问题与回答提取表达能力证据，不直接修改学习档位。 */
    Mono<AbilityEvidenceVO> observe(String agentId, String question, String answer);
}

package cn.adinall.ai.infrastructure.config;

import cn.adinall.ai.domain.business.conversation.adapter.repository.IScenarioRepository;
import cn.adinall.ai.infrastructure.adapter.repository.InMemoryScenarioRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 与具体数字人后端无关的默认实现：场景目录。
 *
 * 「默认」的含义是 @ConditionalOnMissingBean：以后接真实场景服务时，
 * 新写一个同类型的 Bean 覆盖即可，调用方一行不用改。
 *
 * 注：评测策略（AssessStrategy）随训练域一起暂未迁入，待「口语训练回答策略」落地时再加。
 */
@Configuration(proxyBeanMethods = false)
public class DefaultBeansConfiguration {

    /**
     * 默认的口语训练场景目录（内置的自由对话 + 英文面试）。
     * 流程：只有当容器里还没有 IScenarioRepository 时才生效，接真实场景服务时覆盖它。
     */
    @Bean
    @ConditionalOnMissingBean(IScenarioRepository.class)
    public IScenarioRepository defaultScenarioRepository() {
        return new InMemoryScenarioRepository();
    }
}

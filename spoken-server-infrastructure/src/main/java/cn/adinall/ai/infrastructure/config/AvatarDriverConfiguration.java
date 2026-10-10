package cn.adinall.ai.infrastructure.config;

import cn.adinall.ai.domain.business.conversation.adapter.port.IAvatarDriverPort;
import cn.adinall.ai.infrastructure.adapter.port.OpenTalkingAvatarDriver;
import cn.adinall.ai.infrastructure.gateway.OpenTalkingGateway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 当 spoken-ai.provider.avatar=opentalking（或没配，默认就是它）时装配 OpenTalking 适配器。
 *
 * 换后端时只需要新增一个同样结构的配置类 + 一个 IAvatarDriverPort 实现，
 * 领域服务、用例、Controller 一行都不用动 —— 这就是端口存在的意义。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OpenTalkingProperties.class)
@ConditionalOnProperty(prefix = "spoken-ai.provider", name = "avatar", havingValue = "opentalking", matchIfMissing = true)
public class AvatarDriverConfiguration {

    /**
     * 装配 OpenTalking 的 HTTP 网关。只有容器里还没有同类 Bean 时才生效（@ConditionalOnMissingBean），
     * 方便测试时替换成假网关。
     */
    @Bean
    @ConditionalOnMissingBean
    public OpenTalkingGateway openTalkingGateway(OpenTalkingProperties properties) {
        return new OpenTalkingGateway(properties);
    }

    /**
     * 装配 IAvatarDriverPort 的 OpenTalking 实现，也就是默认使用的数字人通道。
     * 产出的 Bean 会被会话领域服务注入；换后端时新增一个同结构的实现即可。
     */
    @Bean
    @ConditionalOnMissingBean(IAvatarDriverPort.class)
    public IAvatarDriverPort openTalkingAvatarDriver(OpenTalkingGateway gateway, OpenTalkingProperties properties) {
        return new OpenTalkingAvatarDriver(gateway, properties);
    }
}
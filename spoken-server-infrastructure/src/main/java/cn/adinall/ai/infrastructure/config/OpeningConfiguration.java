package cn.adinall.ai.infrastructure.config;

import cn.adinall.ai.domain.identity.adapter.port.IOpeningIdentityPort;
import cn.adinall.ai.infrastructure.adapter.port.GuestOpeningIdentityAdapter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.*;
import java.time.Clock;

/**
 * 装配首句使用的时钟和默认身份端口。
 * <p>
 * 功能：装配首句使用的时钟和默认身份端口。
 * <p>
 * 体系架构 / 运行过程：
 * <pre>
 *   Spring 配置 -> Clock / 身份端口 -> 首句准备用例
 * </pre>
 * 案例：
 * <pre>
 *   输入 -> 输出：未提供认证 Bean -> GuestOpeningIdentityAdapter
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>ConditionalOnMissingBean 支持后续认证替换。</li>
 *   <li>UTC 时钟配合学员时区取日期。</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@org.springframework.boot.context.properties.EnableConfigurationProperties(OpeningAvatarProperties.class)
public class OpeningConfiguration {
    @Bean
    public cn.adinall.ai.domain.conversation.adapter.port.IOpeningAvatarPort openingAvatar(
            OpeningAvatarProperties properties,
            @org.springframework.beans.factory.annotation.Value("${spoken-ai.provider.opentalking.default-avatar-id:anchor}") String defaultId) {
        return new cn.adinall.ai.infrastructure.adapter.port.ConfiguredOpeningAvatarAdapter(properties,defaultId);
    }
    @Bean @ConditionalOnMissingBean(Clock.class)
    public Clock openingClock() { return Clock.systemUTC(); }
    @Bean @ConditionalOnMissingBean(IOpeningIdentityPort.class)
    public IOpeningIdentityPort openingIdentity() { return new GuestOpeningIdentityAdapter(); }
}

package cn.adinall.ai.infrastructure.config;

import cn.adinall.ai.domain.coaching.conversation.adapter.port.IAvatarDriverPort;
import cn.adinall.ai.infrastructure.adapter.port.OpenTalkingAvatarDriver;
import cn.adinall.ai.infrastructure.gateway.OpenTalkingGateway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenTalking 适配器装配（由配置开关 spoken-ai.provider.avatar 决定是否生效）
 * <p>
 * 功能：把 HTTP 网关与 IAvatarDriverPort 的 OpenTalking 实现装进 Spring 容器，接上领域端口。
 * <pre>
 *   openTalkingGateway(properties)                 产出 OpenTalkingGateway（@ConditionalOnMissingBean）
 *   openTalkingAvatarDriver(gateway, properties)   产出 IAvatarDriverPort（@ConditionalOnMissingBean）
 * </pre>
 * 运行过程（应用启动时的 Bean 装配）：
 * <pre>
 *   Spring 启动
 *        |
 *        | (1) @ConditionalOnProperty(prefix="spoken-ai.provider", name="avatar",
 *        |         havingValue="opentalking", matchIfMissing=true)
 *        |        |
 *        |        +--> avatar=opentalking 或这一项没配 --> 本配置类生效
 *        |        +--> avatar=其它值                   --> 本配置类整体不生效
 *        |
 *        | (2) @EnableConfigurationProperties(OpenTalkingProperties.class)
 *        |        -> 绑定 spoken-ai.provider.opentalking.*
 *        |
 *        | (3) openTalkingGateway(properties)
 *        |        +--> 容器里已有 OpenTalkingGateway --> 跳过，沿用已有的（测试可塞假网关）
 *        |        v
 *        |     new OpenTalkingGateway(properties)
 *        |         baseUrl 去尾斜杠；JDK HttpClient 连接超时取 connectTimeoutMs；强制 HTTP/1.1
 *        |
 *        | (4) openTalkingAvatarDriver(gateway, properties)
 *        |        +--> 容器里已有 IAvatarDriverPort 实现 --> 跳过
 *        |        v
 *        |     new OpenTalkingAvatarDriver(gateway, properties)
 *        v
 *   领域服务 ConversationServiceImpl 注入的 avatarDriver 就是它（不认识 OpenTalking 这个名字）
 * </pre>
 * 案例：
 * <pre>
 *   输入: spoken-ai.provider.avatar=opentalking（或 yml 里干脆不写这一项）
 *   处理: Spring 调用本类的两个 @Bean 方法
 *   输出: 容器里多出 openTalkingGateway 与名为 openTalkingAvatarDriver 的 IAvatarDriverPort Bean；
 *         ConversationServiceImpl 的 avatarDriver 字段注入的就是它
 *   反例: 配成 spoken-ai.provider.avatar=其它值 时本类不生效 ——
 *         此时必须另有配置类提供 IAvatarDriverPort，否则领域服务会因缺 Bean 起不来
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>matchIfMissing = true：不配 avatar 时默认就用 opentalking，保证空配置也能把服务起起来。</li>
 *   <li>两个 Bean 都带 @ConditionalOnMissingBean：测试里塞一个假网关 / 假驱动即可整条替换，
 *       不必改生产装配代码，也不会出现两个 IAvatarDriverPort 打架。</li>
 *   <li>配置类用 proxyBeanMethods = false：这里的 Bean 之间没有互相调用，
 *       关掉 CGLIB 代理可以少一层间接、启动更快。</li>
 *   <li>扩展点：换后端时新增一个同样结构的配置类 + 一个 IAvatarDriverPort 实现即可，
 *       领域服务、用例、Controller 一行都不用动 —— 这就是端口存在的意义。</li>
 * </ul>
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
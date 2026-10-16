package cn.adinall.ai.infrastructure.config;

import cn.adinall.ai.domain.assessment.pronunciation.adapter.port.IPronunciationPort;
import cn.adinall.ai.infrastructure.adapter.port.OpenPronouncePronunciationAdapter;
import cn.adinall.ai.infrastructure.gateway.OpenPronounceGateway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenPronounce 适配器装配（由配置开关 spoken-ai.provider.pronounce 决定是否生效）
 * <p>
 * 功能：把 HTTP 网关与 IPronunciationPort 的 OpenPronounce 实现装进 Spring 容器，接上领域端口。
 * <pre>
 *   openPronounceGateway(properties)              产出 OpenPronounceGateway（@ConditionalOnMissingBean）
 *   openPronounceAdapter(gateway, properties)     产出 IPronunciationPort（@ConditionalOnMissingBean）
 * </pre>
 * 运行过程（应用启动时的 Bean 装配）：
 * <pre>
 *   Spring 启动
 *        |
 *        | (1) @ConditionalOnProperty(prefix="spoken-ai.provider", name="pronounce",
 *        |         havingValue="openpronounce", matchIfMissing=true)
 *        |        |
 *        |        +--> pronounce=openpronounce 或这一项没配 --> 本配置类生效
 *        |        +--> pronounce=其它值（如 none）           --> 本配置类整体不生效
 *        |
 *        | (2) @EnableConfigurationProperties(OpenPronounceProperties.class)
 *        |        -> 绑定 spoken-ai.provider.openpronounce.*
 *        |
 *        | (3) openPronounceGateway(properties)
 *        |        +--> 容器里已有 OpenPronounceGateway --> 跳过，沿用已有的（测试可塞假网关）
 *        |        v
 *        |     new OpenPronounceGateway(properties)
 *        |         baseUrl 去尾斜杠；JDK HttpClient 连接超时取 connectTimeoutMs；强制 HTTP/1.1
 *        |
 *        | (4) openPronounceAdapter(gateway, properties)
 *        |        +--> 容器里已有 IPronunciationPort 实现 --> 跳过
 *        |        v
 *        |     new OpenPronouncePronunciationAdapter(gateway, properties)
 *        v
 *   发音评测领域服务 PronunciationServiceImpl 注入的 pronunciationPort 就是它
 * </pre>
 * 案例：
 * <pre>
 *   输入: spoken-ai.provider.pronounce=openpronounce（或 yml 里干脆不写这一项）
 *   处理: Spring 调用本类的两个 @Bean 方法
 *   输出: 容器里多出 openPronounceGateway 与名为 openPronounceAdapter 的 IPronunciationPort Bean；
 *         PronunciationServiceImpl 的 pronunciationPort 字段注入的就是它
 *   反例: 配成 spoken-ai.provider.pronounce=none 时本类不生效 ——
 *         此时必须另有配置类提供 IPronunciationPort，否则评测领域服务会因缺 Bean 起不来
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>matchIfMissing = true：不配 pronounce 时默认就用 openpronounce，保证空配置也能把服务起起来
 *       （与 AvatarDriverConfiguration 对 avatar 的处理保持一致）。</li>
 *   <li>两个 Bean 都带 @ConditionalOnMissingBean：测试里塞一个假网关 / 假适配器即可整条替换，
 *       不必改生产装配代码，也不会出现两个 IPronunciationPort 打架。</li>
 *   <li>用 @ConditionalOnProperty 而不是「靠 base-url 是否为空」判断：开关要显式，
 *       否则「部署时忘了配地址」会表现成「服务起来了但一评测就 503」，很难一眼看出来。</li>
 *   <li>扩展点：换一套评测服务（Azure Speech / SpeechAce）时，新增一个同样结构的配置类 +
 *       一个 IPronunciationPort 实现即可，领域服务、用例、Controller 一行都不用动。</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OpenPronounceProperties.class)
@ConditionalOnProperty(prefix = "spoken-ai.provider", name = "pronounce", havingValue = "openpronounce", matchIfMissing = true)
public class PronunciationConfiguration {

    /**
     * 装配 OpenPronounce 的 HTTP 网关。只有容器里还没有同类 Bean 时才生效，
     * 方便测试时替换成假网关。
     */
    @Bean
    @ConditionalOnMissingBean
    public OpenPronounceGateway openPronounceGateway(OpenPronounceProperties properties) {
        return new OpenPronounceGateway(properties);
    }

    /**
     * 装配 IPronunciationPort 的 OpenPronounce 实现，也就是默认使用的发音评测通道。
     * 产出的 Bean 会被评测领域服务注入；换供应商时新增一个同结构的实现即可。
     */
    @Bean
    @ConditionalOnMissingBean(IPronunciationPort.class)
    public IPronunciationPort openPronounceAdapter(OpenPronounceGateway gateway,
                                                   OpenPronounceProperties properties) {
        return new OpenPronouncePronunciationAdapter(gateway, properties);
    }
}

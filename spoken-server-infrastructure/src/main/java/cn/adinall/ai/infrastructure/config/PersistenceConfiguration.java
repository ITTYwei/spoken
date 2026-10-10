package cn.adinall.ai.infrastructure.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 持久化装配（MyBatis Mapper 扫描 + spoken-ai.* 配置绑定）
 * <p>
 * 功能：一个不含 @Bean 方法的配置类，用两个注解把 DAO 接口扫成 Mapper、把 AppProperties 注册成 Bean。
 * <pre>
 *   注解一 @MapperScan("cn.adinall.ai.infrastructure.dao")
 *         把 IConversationTurnDao / ITrainingSessionDao 扫成可注入的 Mapper 代理
 *   注解二 @EnableConfigurationProperties(AppProperties.class)
 *         注册 AppProperties Bean 并开启 spoken-ai 前缀绑定
 * </pre>
 * 体系架构：
 * <pre>
 *   PersistenceConfiguration（本类）
 *        |
 *        +--> @MapperScan --> cn.adinall.ai.infrastructure.dao
 *        |        +--> IConversationTurnDao  --> resources/mybatis/mapper/ConversationTurnMapper.xml
 *        |        +--> ITrainingSessionDao   --> resources/mybatis/mapper/TrainingSessionMapper.xml
 *        |
 *        +--> @EnableConfigurationProperties(AppProperties.class)
 *                 spoken-ai.ready-wait-ms / session-idle-timeout-ms / slot-release-settle-ms /
 *                 max-sessions-per-device / cors-allowed-origins
 *        v
 *   仓储实现（ConversationSessionRepository / ConversationTurnRepository）构造注入 DAO；
 *   ConversationCaseImpl 与 WebCorsConfiguration 注入 IAppConfigPort
 * </pre>
 * 运行过程（一次回合落库，看扫描结果如何被用起来）：
 * <pre>
 *   Spring 启动
 *        |
 *        | (1) @MapperScan 为 dao 包下每个 @Mapper 接口生成代理对象并注册成 Bean
 *        |
 *        | (2) ConversationTurnRepository 构造注入 IConversationTurnDao
 *        v
 *   ConversationTurnRepository.append(turn)
 *        |
 *        +--> turnDao.insert(po)
 *        |        |
 *        |        v
 *        |     ConversationTurnMapper.xml:
 *        |        insert into conversation_turn (...)  useGeneratedKeys="true" keyProperty="id"
 *        v
 *   自增 id 经 po.getId() 回到聚合（App 跟读评测的 turnId）
 * </pre>
 * 案例：
 * <pre>
 *   输入: yml 里 spoken-ai.ready-wait-ms=15000；启动类在 cn.adinall.ai.app 下，
 *         而 DAO 在 cn.adinall.ai.infrastructure.dao 下（不在启动类的子包里）
 *   处理: Spring 处理本类 —— @MapperScan 扫出 IConversationTurnDao / ITrainingSessionDao；
 *         再用 @EnableConfigurationProperties 把 AppProperties 注册成 Bean 并绑定前缀 spoken-ai
 *   输出: 仓储实现能构造注入到 DAO；注入 IAppConfigPort 的地方拿到 AppProperties
 *         （readyWaitMs() 返回 15000，WebCorsConfiguration 用它装配 CORS 规则）
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>显式写 @MapperScan：Spring Boot 的 MyBatis 自动配置默认只扫「启动类所在包」，
 *       启动类在 cn.adinall.ai.app 下，扫不到 cn.adinall.ai.infrastructure.dao。
 *       显式声明最省心，也不会因为启动类搬家而突然「DAO 找不到了」。</li>
 *   <li>本类故意没有 @Bean 方法：它只是把两个「作用在包/类型上」的注解挂在一个类上，
 *       真正的对象由 MyBatis 与 Spring Boot 生成。</li>
 *   <li>AppProperties 类上只有 @ConfigurationProperties（仅声明前缀），本身不是 @Component；
 *       必须靠这里的 @EnableConfigurationProperties 才会进容器，被 IAppConfigPort 的注入点拿到。</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AppProperties.class)
@MapperScan("cn.adinall.ai.infrastructure.dao")
public class PersistenceConfiguration {
}
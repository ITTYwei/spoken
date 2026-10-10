package cn.adinall.ai.infrastructure.config;

import cn.adinall.ai.domain.agent.service.IChatService;
import cn.adinall.ai.domain.conversation.adapter.port.IReplyGeneratorPort;
import cn.adinall.ai.infrastructure.adapter.port.AgentReplyGenerator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 回复生成适配器装配（把 Google ADK 智能体接成 IReplyGeneratorPort）
 * <p>
 * 功能：产出 IReplyGeneratorPort 的 agent 实现，供会话领域服务在「Java 自己跑 LLM」时使用。
 * <pre>
 *   agentReplyGenerator(chatService, props)   产出 IReplyGeneratorPort（@ConditionalOnMissingBean）
 * </pre>
 * 运行过程（应用启动时的 Bean 装配）：
 * <pre>
 *   Spring 启动
 *        |
 *        | (1) @EnableConfigurationProperties(ReplyProperties.class)
 *        |        -> 绑定 spoken-ai.reply.*（agent-id）
 *        |
 *        | (2) agentReplyGenerator(chatService, props)
 *        |        +--> 容器里已有 IReplyGeneratorPort 实现 --> 跳过（测试可塞假的）
 *        |        v
 *        |     new AgentReplyGenerator(chatService, props)
 *        v
 *   领域服务 ConversationServiceImpl 注入的 replyGenerator 就是它
 * </pre>
 * 案例：
 * <pre>
 *   输入: 启动时容器里有 ChatService（domain.agent 的 @Service）
 *   处理: 本类产出 AgentReplyGenerator
 *   输出: 领域服务拿到 IReplyGeneratorPort，id()="agent"
 *         spoken-ai.llm-owner=agent 时它才会被真正调用
 * </pre>
 * 设计要点：
 * <ul>
 *   <li><b>刻意不加 @ConditionalOnProperty 挂在 llm-owner 上</b>：那个开关是「运行期走哪条路」，
 *       不是「要不要这个 Bean」。把 Bean 的存在与否拴在开关上，会导致
 *       llm-owner=opentalking 时 IReplyGeneratorPort 缺失、领域服务直接因缺 Bean 起不来 ——
 *       也就是「一关开关，服务就启动不了」。开关的判断放在用例层做。</li>
 *   <li>@ConditionalOnMissingBean 保留：测试里塞一个假的生成器即可整条替换，不必改生产装配。</li>
 *   <li>本类不校验 agent 是否真的装配成功（那是 agent 脚手架的事）：
 *       agent 未启用时 Bean 照样创建，真正调用时才由 ChatService 抛 E0001 ——
 *       这样「agent 没开」不会把整个后端拖死，只影响这一条链路。</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ReplyProperties.class)
public class ReplyConfiguration {

    /**
     * 装配 IReplyGeneratorPort 的智能体实现。
     * 只有容器里还没有同类 Bean 时才生效，方便测试时替换成假实现。
     */
    @Bean
    @ConditionalOnMissingBean(IReplyGeneratorPort.class)
    public IReplyGeneratorPort agentReplyGenerator(IChatService chatService, ReplyProperties properties) {
        return new AgentReplyGenerator(chatService, properties);
    }
}

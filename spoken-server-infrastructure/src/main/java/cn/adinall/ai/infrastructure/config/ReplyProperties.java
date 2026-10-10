package cn.adinall.ai.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 回复生成配置（@ConfigurationProperties(prefix = "spoken-ai.reply")）
 * <p>
 * 功能：把「Java 自己跑哪个智能体来产出数字人的回答」这件事绑成 Bean。
 * <pre>
 *   agentId   用哪个已装配的智能体（对应 agent/*.yml 里的 agent.agent-id），默认 100003（口语教练 SpokenCoach）
 * </pre>
 * 配置样例与消费方：
 * <pre>
 *   spoken-ai:
 *     llm-owner: agent              # 总开关：谁产出回答文本（agent | opentalking）
 *     reply:
 *       agent-id: "100003"          # 走 agent 时用哪个智能体
 *        |
 *        v
 *   ReplyConfiguration: @EnableConfigurationProperties(ReplyProperties.class)
 *        |
 *        +--> new AgentReplyGenerator(chatService, props)
 *        v
 *   ConversationServiceImpl 注入的 replyGenerator 就是它
 * </pre>
 * 案例：
 * <pre>
 *   输入: yml 不写 spoken-ai.reply
 *   处理: 用默认 agentId="100003"
 *   输出: 走 agent 链路时调用配置里那个「口语教练 SpokenCoach」智能体
 *   注意: 这个 id 必须真的被装配进容器（即 agent/*.yml 里有这个 agent-id，
 *         且 ai.agent.config.enabled=true），否则调用期抛 E0001（智能体不存在）
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>agentId 放在基础设施配置里而不是领域：领域只提「给我生成一句回复」，
 *       「用哪个模型/哪个智能体」是部署细节。</li>
 *   <li>默认值就是本项目 agent 配置里的 id（100003）：不写配置也能跑通，
 *       与 OpenTalkingProperties / OpenPronounceProperties 的默认值风格一致。</li>
 *   <li>注意这里的 100003 是 **agent-id**（agent.agent-id），不是配置表的 key（spokenServer）——
 *       两者作用不同，写错了表现为调用期 E0001。</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "spoken-ai.reply")
public class ReplyProperties {

    /** 走 agent 链路时用哪个智能体（agent/*.yml 里的 agent.agent-id）。 */
    private String agentId = "100003";

    public String getAgentId() {
        return agentId;
    }

    public void setAgentId(String agentId) {
        this.agentId = agentId;
    }
}

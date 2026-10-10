package cn.adinall.ai.domain.agent.model.valobj.properties;

import cn.adinall.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

/**
 * 智能体自动装配配置属性（yml 与装配体系之间唯一的配置入口）
 * <p>
 * 功能：把 <code>ai.agent.config.*</code> 这段 yml 绑定成强类型对象，供启动期的
 * AiAgentAutoConfig 决定「要不要装配、装配哪些智能体」。
 * <pre>
 *   enabled   是否启用自动装配，默认 false（关闭时整套 agent 能力不参与启动）
 *   tables    key -> 智能体配置表；key 只是配置里的名字，真正的身份是表内 agent.agentId
 * </pre>
 * 体系架构（配置如何变成运行期智能体）：
 * <pre>
 *   application.yml / application-dev.yml
 *        |
 *        | ai.agent.config:
 *        |   enabled: true
 *        |   tables:
 *        |     demo:                       <-- Map 的 key
 *        |       app-name: demo-app
 *        |       agent: {agent-id: "3001", agent-name: 对话助手}
 *        |       module: {ai-api: {...}, chat-model: {...}, agents: [...], runner: {...}}
 *        v
 *   AiAgentAutoConfigProperties   <-- 本类（@ConfigurationProperties(prefix="ai.agent.config")）
 *        |
 *        | 由 @EnableConfigurationProperties 注册（在 AiAgentAutoConfig 上标注）
 *        v
 *   AiAgentAutoConfig.onApplicationEvent(ApplicationReadyEvent)
 *        |  isEnabled() == false 或 tables 为空 --> 跳过
 *        v
 *   IArmoryService.acceptArmoryAgents(tables.values())
 *        |
 *        v
 *   Spring 容器 bean: agentId -> AiAgentRegisterVO
 * </pre>
 * 案例：
 * <pre>
 *   输入: ai.agent.config.enabled=true
 *         ai.agent.config.tables.demo.app-name=demo-app
 *         ai.agent.config.tables.demo.agent.agent-id=3001
 *   处理: 松散绑定 kebab-case -> 驼峰（app-name -> appName, agent-id -> agentId）
 *   输出: AiAgentAutoConfigProperties{enabled=true, tables={"demo": AiAgentConfigTableVO{
 *         appName="demo-app", agent=Agent{agentId="3001"}}}}
 *         另一例: yml 完全不写 ai.agent.config --> enabled=false, tables=null --> 启动期跳过装配
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>enabled 默认 false 且显式判空：agent 能力是后接的，没配也要能正常启动
 *       「数字人对话」那一套能力，所以这里是可选的开关而不是必需的配置。</li>
 *   <li>ignoreInvalidFields = true：yml 里出现未知/写错的字段名时不阻塞启动，
 *       代价是拼错的配置会静默失效（排查时以启动日志里打印的 tables JSON 为准）。</li>
 *   <li>tables 用 Map 而不是 List：key 用来在 yml 里组织配置，顺序不影响装配结果，
 *       真正的 bean 名只由表内 agent.agentId 决定。</li>
 *   <li>本类只声明字段，不做校验：字段层面的错误（如 model 为空）留给装配链各节点去抛，
 *       保证「配置能不能用」只有一个判定位置。</li>
 * </ul>
 */
@Data
@ConfigurationProperties(prefix = "ai.agent.config", ignoreInvalidFields = true)
public class AiAgentAutoConfigProperties {

    /**
     * 是否启用AI Agent自动装配
     */
    private boolean enabled = false;

    private Map<String, AiAgentConfigTableVO> tables;

}

package cn.adinall.ai.config;

import cn.adinall.ai.infrastructure.config.agent.AiAgentAutoConfigProperties;
import cn.adinall.ai.infrastructure.config.agent.AiAgentConfigTableVO;
import cn.adinall.ai.infrastructure.gateway.agent.armory.IArmoryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Configuration;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Map;

/**
 * 智能体自动装配触发器（启动期的装配总开关）
 * <p>
 * 功能：监听 Spring Boot 的 ApplicationReadyEvent，在「应用已就绪」这一刻读配置、
 * 决定是否装配，并把全部智能体配置交给装配服务（IArmoryService）。
 * <pre>
 *   onApplicationEvent(ApplicationReadyEvent)  检查开关与配置 --> 调 armoryService 完成装配
 *   两个提前返回分支                            enabled=false / tables 为空 --> 只打日志，不装配
 * </pre>
 * 运行过程：
 * <pre>
 *   Spring Boot 启动完成（所有 bean 就绪、端口已监听）
 *        |
 *        v
 *   ApplicationReadyEvent --> onApplicationEvent(event)
 *        |
 *        | (1) aiAgentAutoConfigProperties.isEnabled() == false
 *        |        |--> log "Ai Agent 自动装配未启用（ai.agent.config.enabled=false），跳过" --> return
 *        |
 *        | (2) tables = getTables(); tables == null || tables.isEmpty()
 *        |        |--> log "Ai Agent 自动装配：未配置任何智能体，跳过" --> return
 *        |
 *        | (3) log "Ai Agent 智能体装配 {配置摘要}"（只含非敏感字段，apiKey 已脱敏）
 *        |
 *        | (4) armoryService.acceptArmoryAgents(new ArrayList&lt;&gt;(tables.values()))
 *        |        |        RootNode -> AiApiNode -> ChatModelNode -> AgentNode
 *        |        |               -> AgentWorkflowNode -> RunnerNode
 *        |        |        v
 *        |        |     容器注册 bean: agentId -> AiAgentRegisterVO
 *        |        +--> 任何 Exception --> throw new RuntimeException(e) 终止启动
 *        v
 *   应用启动成功，ChatService 可 getAiAgentRegisterVO(agentId) 发起对话
 * </pre>
 * 案例：
 * <pre>
 *   输入: ai.agent.config.enabled=true,
 *         ai.agent.config.tables.demo={app-name: demo-app, agent: {agent-id: "3001"}, ...}
 *   处理: 打印配置摘要（不含密钥）后 acceptArmoryAgents(["demo" 那张表])
 *   输出: void；副作用 = 容器里多出 "3001" 这个 AiAgentRegisterVO bean。
 *         异常: 装配过程抛 AppException（如 runner.agentName 为空）--> 包装成 RuntimeException 让启动失败
 *         对照: enabled=false 时日志 "跳过"，容器里没有任何 agent bean，但应用照常启动
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>用 ApplicationReadyEvent 而不是 @PostConstruct：装配要 new Runner、连 MCP / 读技能目录，
 *       必须等容器和外部依赖都就绪之后再跑，否则会出现半初始化状态。</li>
 *   <li>两条提前返回让「数字人对话」能力可以独立启动：agent 配置缺失或关闭不影响整个应用。</li>
 *   <li>装配异常一律转成 RuntimeException 抛出：启动期失败必须让应用起不来，
 *       否则会出现「应用活着但所有对话都报 E0001」的假健康状态。</li>
 *   <li>本类只做「读开关 + 转发」，具体步骤全在领域侧装配链里，
 *       因此新增装配步骤不需要改这里。</li>
 *   <li>启动日志只打非敏感字段（见 summarize / mask）：配置对象里含 apiKey 与
 *       sseEndpoint 中的 api_key，整对象序列化会直接把明文密钥写进磁盘日志。</li>
 * </ul>
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(AiAgentAutoConfigProperties.class)
public class AiAgentAutoConfig implements ApplicationListener<ApplicationReadyEvent> {

    @Resource
    private AiAgentAutoConfigProperties aiAgentAutoConfigProperties;

    @Resource
    private IArmoryService armoryService;

    /** 响应应用启动事件，初始化启动阶段所需的运行配置。 */
    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        // Agent 是后续才接的能力；没启用（ai.agent.config.enabled=false）或没配任何智能体时，
        // 直接跳过，让「数字人对话」这套能力可以独立启动，不必先备齐 agent 配置。
        if (!aiAgentAutoConfigProperties.isEnabled()) {
            log.info("Ai Agent 自动装配未启用（ai.agent.config.enabled=false），跳过");
            return;
        }
        Map<String, AiAgentConfigTableVO> tables = aiAgentAutoConfigProperties.getTables();
        if (tables == null || tables.isEmpty()) {
            log.info("Ai Agent 自动装配：未配置任何智能体，跳过");
            return;
        }
        try {
            // 注意：这里绝对不能 JSON.toJSONString(tables) 直接整对象打日志 ——
            // 该对象含 module.aiApi.apiKey 和 toolMcpList[].sse.sseEndpoint 里的密钥，
            // 一打就会把明文密钥写进磁盘日志（曾因此真实泄露）。
            // 只打非敏感字段，apiKey 单独脱敏。
            log.info("Ai Agent 智能体装配 {}", summarize(tables));

            armoryService.acceptArmoryAgents(new ArrayList<>(tables.values()));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** 拼装不含密钥的配置摘要，用于启动日志。包级可见以便单测直接验证不泄露密钥。 */
    static String summarize(Map<String, AiAgentConfigTableVO> tables) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, AiAgentConfigTableVO> entry : tables.entrySet()) {
            AiAgentConfigTableVO table = entry.getValue();
            if (table == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append("key=").append(entry.getKey()).append(", appName=").append(table.getAppName());

            AiAgentConfigTableVO.Agent agent = table.getAgent();
            if (agent != null) {
                sb.append(", agentId=").append(agent.getAgentId());
            }

            AiAgentConfigTableVO.Module module = table.getModule();
            if (module == null) {
                continue;
            }
            AiAgentConfigTableVO.Module.AiApi aiApi = module.getAiApi();
            if (aiApi != null) {
                sb.append(", baseUrl=").append(aiApi.getBaseUrl())
                        .append(", apiKey=").append(mask(aiApi.getApiKey()));
            }
            AiAgentConfigTableVO.Module.ChatModel chatModel = module.getChatModel();
            if (chatModel != null) {
                sb.append(", model=").append(chatModel.getModel());
            }
            if (module.getRunner() != null) {
                sb.append(", runnerAgent=").append(module.getRunner().getAgentName());
            }
        }
        return sb.toString();
    }

    /** 密钥脱敏：只留前 3 位与后 4 位，长度不足则整串打码。 */
    static String mask(String secret) {
        if (secret == null || secret.isEmpty()) {
            return "(empty)";
        }
        if (secret.length() <= 12) {
            return "****";
        }
        return secret.substring(0, 3) + "****" + secret.substring(secret.length() - 4);
    }

}


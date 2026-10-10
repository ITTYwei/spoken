package cn.adinall.ai.config;

import cn.adinall.ai.domain.agent.model.valobj.properties.AiAgentAutoConfigProperties;
import cn.adinall.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import cn.adinall.ai.domain.agent.service.IArmoryService;
import com.alibaba.fastjson.JSON;
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
 *        | (3) log "Ai Agent 智能体装配 {tables JSON}"
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
 *   处理: 打印配置 JSON 后 acceptArmoryAgents(["demo" 那张表])
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
            log.info("Ai Agent 智能体装配 {}", JSON.toJSONString(tables.values()));

            armoryService.acceptArmoryAgents(new ArrayList<>(tables.values()));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

}


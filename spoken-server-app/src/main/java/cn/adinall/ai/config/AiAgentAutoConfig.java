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


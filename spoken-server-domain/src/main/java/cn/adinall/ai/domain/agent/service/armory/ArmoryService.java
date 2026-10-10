package cn.adinall.ai.domain.agent.service.armory;

import cn.adinall.ai.domain.agent.model.entity.ArmoryCommandEntity;
import cn.adinall.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import cn.adinall.ai.domain.agent.model.valobj.AiAgentRegisterVO;
import cn.adinall.ai.domain.agent.service.IArmoryService;
import cn.adinall.ai.domain.agent.service.armory.factory.DefaultArmoryFactory;
import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;

/**
 * 智能体装配服务（armory 体系的对外入口）
 * <p>
 * 功能：接收一批「智能体配置表」，逐个驱动装配责任链，把每个智能体装配成一个
 * 可运行的 InMemoryRunner 并注册进 Spring 容器。
 * <pre>
 *   acceptArmoryAgents(List&lt;AiAgentConfigTableVO&gt;)  批量装配：一张配置表 = 一个智能体
 * </pre>
 * 整体运行过程（一次批量装配）：
 * <pre>
 *   AiAgentAutoConfig（启动期，@PostConstruct）
 *        |
 *        | tables = ai.agent.config.tables.values()
 *        v
 *   acceptArmoryAgents(tables)
 *        |
 *        +--> for table in tables:
 *        |        |
 *        |        | (1) defaultArmoryFactory.armoryStrategyHandler() --> RootNode
 *        |        |
 *        |        | (2) handler.apply(ArmoryCommandEntity{aiAgentConfigTableVO=table},
 *        |        |                  new DynamicContext())   每个智能体一个独立上下文
 *        |        |        |
 *        |        |        v
 *        |        |     RootNode -> AiApiNode -> ChatModelNode
 *        |        |             -> AgentNode -> AgentWorkflowNode(循环) -> RunnerNode
 *        |        |        |
 *        |        |        v
 *        |        |     registerBean(agentId, AiAgentRegisterVO.class, vo)
 *        |        |
 *        v        v
 *   Spring 容器里出现 bean: agentId -> AiAgentRegisterVO
 *        |
 *        v
 *   ChatService.getAiAgentRegisterVO(agentId) 取用
 * </pre>
 * 案例：
 * <pre>
 *   输入: tables = [AiAgentConfigTableVO{appName="demo-app",
 *                     agent={agentId="3001", agentName="对话助手"},
 *                     module={aiApi={baseUrl="https://api.deepseek.com", apiKey="sk-***"},
 *                             chatModel={model="deepseek-chat"},
 *                             agents=[{name="main-agent"}]}}]
 *   处理: acceptArmoryAgents(tables) 触发整条装配链
 *   输出: 无返回值；副作用是 Spring 容器里注册了名为 "3001" 的 AiAgentRegisterVO
 *         （失败时沿链抛出 AppException，例如 ILLEGAL_PARAMETER）
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>入口只做「遍历 + 起链」，具体装配逻辑全在节点里 —— 新增一类装配步骤不必改本类。</li>
 *   <li>每个配置表 new 一个 DynamicContext：上下文是一次装配过程的状态袋，
 *       多个智能体之间绝不能共用，否则 agentGroup / currentStepIndex 会互相污染。</li>
 *   <li>装配是启动期一次性动作，失败要立刻抛出（AppException）让应用起不来，
 *       而不是生成一个「半装配」的 bean 等到对话时才报错。</li>
 * </ul>
 */
@Slf4j
@Service
public class ArmoryService implements IArmoryService {

    @Resource
    private DefaultArmoryFactory defaultArmoryFactory;

    @Override
    public void acceptArmoryAgents(List<AiAgentConfigTableVO> tables) throws Exception {
        for (AiAgentConfigTableVO table : tables) {
            StrategyHandler<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, AiAgentRegisterVO> handler = defaultArmoryFactory.armoryStrategyHandler();
            handler.apply(
                    ArmoryCommandEntity.builder()
                            .aiAgentConfigTableVO(table)
                            .build(),
                    new DefaultArmoryFactory.DynamicContext());
        }
    }

}

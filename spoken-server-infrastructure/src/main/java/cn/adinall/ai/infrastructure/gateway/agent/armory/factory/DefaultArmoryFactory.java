package cn.adinall.ai.infrastructure.gateway.agent.armory.factory;

import cn.adinall.ai.infrastructure.gateway.agent.model.ArmoryCommandEntity;
import cn.adinall.ai.infrastructure.config.agent.AiAgentConfigTableVO;
import cn.adinall.ai.infrastructure.gateway.agent.model.AiAgentRegisterVO;
import cn.adinall.ai.infrastructure.gateway.agent.armory.node.RootNode;
import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.SequentialAgent;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 智能体装配工厂（armory 体系的入口 + 装配过程上下文容器）
 * <p>
 * 功能：对外提供两个能力 —— 拿到装配责任链的起点，以及按 agentId 取回装配产物。
 * <pre>
 *   armoryStrategyHandler()   返回责任链根节点 RootNode，调用方自己 apply 触发装配
 *   getAiAgentRegisterVO(id)  按 agentId 从 Spring 容器取已装配的智能体
 *   DynamicContext            节点之间传递过程数据的上下文（静态内部类）
 * </pre>
 * 体系架构（装配期产出 -> 运行期取用）：
 * <pre>
 *   AiAgentAutoConfig（ApplicationReadyEvent）
 *        |
 *        v
 *   ArmoryService.acceptArmoryAgents(tables)
 *        |  armoryStrategyHandler()
 *        v
 *   RootNode --> AiApiNode --> ChatModelNode --> AgentNode
 *                                             --> AgentWorkflowNode（回流）
 *                                             --> RunnerNode
 *        |  过程数据全部写进 DynamicContext:
 *        |    openAiApi / chatModel / agentGroup / currentAgentWorkflow
 *        |    currentStepIndex / dataObjects
 *        v
 *   RunnerNode: registerBean(agentId, AiAgentRegisterVO.class, vo)
 *        |
 *        v
 *   ChatService.getAiAgentRegisterVO(agentId) --> InMemoryRunner --> 发起对话
 * </pre>
 * 运行过程（DynamicContext 的一生）：
 * <pre>
 *   new DynamicContext()                     装配开始（每个配置表一个）
 *        |
 *        | AiApiNode:          setOpenAiApi(openAiApi)
 *        | ChatModelNode:      setChatModel(chatModel)
 *        | AgentNode:          getAgentGroup().put(name, LlmAgent)
 *        | AgentWorkflowNode:  setCurrentAgentWorkflow(..) / addCurrentStepIndex()
 *        |                     queryAgentList(subAgentNames) 按名字取子体
 *        v
 *   RunnerNode 读出 agentGroup.get(runner.agentName) 当根，装配结束，上下文即被丢弃
 * </pre>
 * 案例：
 * <pre>
 *   输入: agentId = "3001"（已在启动期装配完成）
 *   处理: getAiAgentRegisterVO("3001") --> applicationContext.getBean("3001", AiAgentRegisterVO.class)
 *   输出: AiAgentRegisterVO{appName="demo-app", agentId="3001", agentName="对话助手",
 *         runner=InMemoryRunner}; 未装配/写错 id 时抛 NoSuchBeanDefinitionException
 *   另一例: armoryStrategyHandler() 返回 RootNode 实例，链上各节点由 @Resource 注入的固定引用串成
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>装配期与运行期彻底分开：装配产物按 agentId 注册进容器，运行期只做 getBean，
 *       不做重新装配，因此 getAiAgentRegisterVO 无锁且极快。</li>
 *   <li>DynamicContext 是节点链唯一的传递通道：节点之间不互相持有状态，
 *       新增节点只需在链上挂一环，不必改已有节点。</li>
 *   <li>getAiAgentRegisterVO 直接依赖 ApplicationContext：容器里没有对应 bean 时
 *       抛 NoSuchBeanDefinitionException，调用方（ChatService）必须先确认装配已完成。</li>
 *   <li>dataObjects 是逃生舱（setValue/getValue）：新节点需要传递临时对象时，
 *       可以先放这里，不必每次给 DynamicContext 加字段。</li>
 * </ul>
 *
 * @author 魏玉石
 * 2025/12/17 08:16
 */
@Service
public class DefaultArmoryFactory {

    @Resource
    private ApplicationContext applicationContext;

    @Resource
    private RootNode rootNode;

    /** 返回智能体装配策略链的入口节点。 */
    public StrategyHandler<ArmoryCommandEntity, DynamicContext, AiAgentRegisterVO> armoryStrategyHandler() {
        return rootNode;
    }

    /** 读取已装配的智能体注册信息。 */
    public AiAgentRegisterVO getAiAgentRegisterVO(String agentId) {
        return applicationContext.getBean(agentId, AiAgentRegisterVO.class);
    }

    /**
     * 定义一个上下文对象，用于各个节点串联的时候，写入数据和使用数据
     */
    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class DynamicContext {

        /**
         * 大模型接口配置。
         */
        private OpenAiApi openAiApi;

        /**
         * 大模型对话模型配置。
         */
        private ChatModel chatModel;

        /**
         * 智能体配置组
         */
        private Map<String, BaseAgent> agentGroup = new HashMap<>();

        private AtomicInteger currentStepIndex = new AtomicInteger(0);

        private AiAgentConfigTableVO.Module.AgentWorkflow currentAgentWorkflow;

        private Map<String, Object> dataObjects = new HashMap<>();

        /** 设置装配上下文中的指定值。 */
        public <T> void setValue(String key, T value) {
            dataObjects.put(key, value);
        }

        /** 读取装配上下文中的指定值。 */
        public <T> T getValue(String key) {
            return (T) dataObjects.get(key);
        }

        /** 按名称从装配上下文查找智能体实例。 */
        public List<BaseAgent> queryAgentList(List<String> agentNames) {
            if (agentNames == null || agentNames.isEmpty() || agentGroup == null) {
                return Collections.emptyList();
            }

            List<BaseAgent> agents = new ArrayList<>();
            for (String name : agentNames) {
                BaseAgent agent = agentGroup.get(name);
                if (agent != null) {
                    agents.add(agent);
                }
            }

            return agents;
        }

        /** 推进当前装配步骤索引。 */
        public void addCurrentStepIndex() {
            currentStepIndex.incrementAndGet();
        }

        /** 读取当前装配步骤索引。 */
        public int getCurrentStepIndex() {
            return currentStepIndex.get();
        }

    }

}

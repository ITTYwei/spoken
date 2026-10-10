package cn.adinall.ai.domain.agent.service.armory.node.workflow;

import cn.adinall.ai.domain.agent.model.entity.ArmoryCommandEntity;
import cn.adinall.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import cn.adinall.ai.domain.agent.model.valobj.AiAgentRegisterVO;
import cn.adinall.ai.domain.agent.model.valobj.enums.AgentTypeEnum;
import cn.adinall.ai.domain.agent.service.armory.AbstractArmorySupport;
import cn.adinall.ai.domain.agent.service.armory.factory.DefaultArmoryFactory;
import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.LoopAgent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 循环工作流装配节点（装配链回流环上的第一站：把子智能体包成 LoopAgent）
 * <p>
 * 功能：读取上下文里当前这条工作流配置（由 AgentWorkflowNode 按步号放好），
 * 用它的 subAgents 从 agentGroup 取回已建好的子智能体，造一个 LoopAgent 并登记回 agentGroup。
 * <pre>
 *   doApply()  造 LoopAgent(name/description/subAgents/maxIterations) --> agentGroup.put --> 路由
 *   get()      返回 getBean("agentWorkflowNode")，把控制权交回 AgentWorkflowNode（回流）
 * </pre>
 * 运行过程（回流环，本节点是其中一环）：
 * <pre>
 *   AgentWorkflowNode.get(): type="loop" --> loopAgentNode
 *        |
 *        v
 *   doApply(requestParameter, dynamicContext)
 *        |
 *        | (1) currentAgentWorkflow = dynamicContext.getCurrentAgentWorkflow()
 *        |     （AgentWorkflowNode.doApply 已把 agentWorkflows.get(step) 放进上下文并步号 +1）
 *        |
 *        | (2) subAgentNames = currentAgentWorkflow.getSubAgents()
 *        |     subAgents = dynamicContext.queryAgentList(subAgentNames)
 *        |     （按名字从 agentGroup 取，名字不存在的元素被静默跳过）
 *        |
 *        | (3) LoopAgent.builder()
 *        |        .name(currentAgentWorkflow.getName())
 *        |        .description(currentAgentWorkflow.getDescription())
 *        |        .subAgents(subAgents)
 *        |        .maxIterations(currentAgentWorkflow.getMaxIterations())
 *        |
 *        | (4) agentGroup.put(currentAgentWorkflow.getName(), loopAgent)
 *        v
 *   get() --> getBean("agentWorkflowNode") --> AgentWorkflowNode 处理下一条工作流
 *        （全部配置处理完后，由 AgentWorkflowNode.get() 转向 RunnerNode）
 * </pre>
 * 案例：
 * <pre>
 *   输入: currentAgentWorkflow = {type="loop", name="retry",
 *                                subAgents=["judge-agent"], maxIterations=3}
 *         dynamicContext.agentGroup = {"judge-agent": LlmAgent}
 *   处理: queryAgentList(["judge-agent"]) 取回 1 个子体 --> LoopAgent("retry", maxIterations=3)
 *   输出: 无返回值；agentGroup 变成 {"judge-agent": LlmAgent, "retry": LoopAgent}
 *         随后回流到 AgentWorkflowNode，继续处理下一条工作流配置
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>回流靠 getBean("agentWorkflowNode") 而不是字段注入：本节点不持有 AgentWorkflowNode 引用，
 *       每处理完一条就按名字从容器取回它，环的入口永远只有一个。</li>
 *   <li>本节点不负责「选哪条配置」：currentAgentWorkflow 由 AgentWorkflowNode 按步号写入，
 *       所以一次 doApply 只装配一条工作流，配置条数由 AgentWorkflowNode 的循环决定。</li>
 *   <li>maxIterations 直接透传（配置默认 3，见 AgentWorkflow 的字段默认值），
 *       本节点不做取值范围校验：写成 0 或负数在这里不会报错。</li>
 *   <li>subAgents 里的名字必须在 agentGroup 里已存在：queryAgentList 只收集取到的，
 *       取不到的静默丢弃，所以拼错名字得到的是一棵「少挂了子体」的树，而不是异常。</li>
 * </ul>
 */
@Slf4j
@Service("loopAgentNode")
public class LoopAgentNode extends AbstractArmorySupport {

    @Override
    protected AiAgentRegisterVO doApply(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        log.info("Ai Agent 装配操作 - LoopAgentNode");

        AiAgentConfigTableVO.Module.AgentWorkflow currentAgentWorkflow = dynamicContext.getCurrentAgentWorkflow();

        List<String> subAgentNames = currentAgentWorkflow.getSubAgents();
        List<BaseAgent> subAgents = dynamicContext.queryAgentList(subAgentNames);

        LoopAgent loopAgent =
                LoopAgent.builder()
                        .name(currentAgentWorkflow.getName())
                        .description(currentAgentWorkflow.getDescription())
                        .subAgents(subAgents)
                        .maxIterations(currentAgentWorkflow.getMaxIterations())
                        .build();

        dynamicContext.getAgentGroup().put(currentAgentWorkflow.getName(), loopAgent);

        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, AiAgentRegisterVO> get(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        return getBean("agentWorkflowNode");
    }

}

package cn.adinall.ai.domain.agent.service.armory.node.workflow;

import cn.adinall.ai.domain.agent.model.entity.ArmoryCommandEntity;
import cn.adinall.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import cn.adinall.ai.domain.agent.model.valobj.AiAgentRegisterVO;
import cn.adinall.ai.domain.agent.model.valobj.enums.AgentTypeEnum;
import cn.adinall.ai.domain.agent.service.armory.AbstractArmorySupport;
import cn.adinall.ai.domain.agent.service.armory.factory.DefaultArmoryFactory;
import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.ParallelAgent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 并行工作流装配节点（装配链回流环上的第二站：把子智能体包成 ParallelAgent）
 * <p>
 * 功能：用上下文里当前这条工作流配置的 subAgents 从 agentGroup 取子智能体，
 * 造一个 ParallelAgent 并登记回 agentGroup，随后回流到 AgentWorkflowNode 处理下一条。
 * <pre>
 *   doApply()  造 ParallelAgent(name/description/subAgents) --> agentGroup.put --> 路由
 *   get()      返回 getBean("agentWorkflowNode")，把控制权交回 AgentWorkflowNode（回流）
 * </pre>
 * 运行过程（回流环，本节点是其中一环）：
 * <pre>
 *   AgentWorkflowNode.get(): AgentTypeEnum.formType("parallel").getNode() = "parallelAgentNode"
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
 *        | (3) ParallelAgent.builder()
 *        |        .name(currentAgentWorkflow.getName())
 *        |        .description(currentAgentWorkflow.getDescription())
 *        |        .subAgents(subAgents)
 *        |
 *        | (4) agentGroup.put(currentAgentWorkflow.getName(), parallelAgent)
 *        v
 *   get() --> getBean("agentWorkflowNode") --> AgentWorkflowNode 处理下一条工作流
 *        （全部配置处理完后，由 AgentWorkflowNode.get() 转向 RunnerNode）
 * </pre>
 * 案例：
 * <pre>
 *   输入: currentAgentWorkflow = {type="parallel", name="research",
 *                                subAgents=["search-agent", "summary-agent"]}
 *         dynamicContext.agentGroup = {"search-agent": LlmAgent, "summary-agent": LlmAgent}
 *   处理: queryAgentList 取回 2 个子体 --> ParallelAgent("research", subAgents=[两个])
 *   输出: 无返回值；agentGroup 多出 "research" -> ParallelAgent
 *         随后回流到 AgentWorkflowNode，继续处理下一条工作流配置
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>与 LoopAgentNode 的差别只在构造器：本类不透传 maxIterations，
 *       配置里就算写了 max-iterations 也不会进入 ParallelAgent。</li>
 *   <li>回流靠 getBean("agentWorkflowNode")：本节点不持有 AgentWorkflowNode 引用，
 *       每处理完一条就从容器取回它，环的入口始终唯一。</li>
 *   <li>本节点不判断「该不该处理这条」：currentAgentWorkflow 由 AgentWorkflowNode 按步号选好，
 *       节点本身只负责装配，配置条数由 AgentWorkflowNode 的循环控制。</li>
 *   <li>子体名字拼错不会报错：queryAgentList 只收集在 agentGroup 里取到的，
 *       取不到的静默丢弃，结果是并行体少挂了子体而不是装配失败。</li>
 * </ul>
 */
@Slf4j
@Service("parallelAgentNode")
public class ParallelAgentNode extends AbstractArmorySupport {

    @Override
    protected AiAgentRegisterVO doApply(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        log.info("Ai Agent 装配操作 - ParallelAgentNode");

        AiAgentConfigTableVO.Module.AgentWorkflow currentAgentWorkflow = dynamicContext.getCurrentAgentWorkflow();

        List<String> subAgentNames = currentAgentWorkflow.getSubAgents();
        List<BaseAgent> subAgents = dynamicContext.queryAgentList(subAgentNames);

        ParallelAgent parallelAgent =
                ParallelAgent.builder()
                        .name(currentAgentWorkflow.getName())
                        .description(currentAgentWorkflow.getDescription())
                        .subAgents(subAgents)
                        .build();

        dynamicContext.getAgentGroup().put(currentAgentWorkflow.getName(), parallelAgent);

        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, AiAgentRegisterVO> get(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        return getBean("agentWorkflowNode");
    }
}

package cn.adinall.ai.infrastructure.gateway.agent.armory.node.workflow;

import cn.adinall.ai.infrastructure.gateway.agent.model.ArmoryCommandEntity;
import cn.adinall.ai.infrastructure.config.agent.AiAgentConfigTableVO;
import cn.adinall.ai.infrastructure.gateway.agent.model.AiAgentRegisterVO;
import cn.adinall.ai.infrastructure.gateway.agent.armory.AbstractArmorySupport;
import cn.adinall.ai.infrastructure.gateway.agent.armory.factory.DefaultArmoryFactory;
import cn.adinall.ai.infrastructure.gateway.agent.armory.node.RunnerNode;
import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.ParallelAgent;
import com.google.adk.agents.SequentialAgent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;

/**
 * 串行工作流装配节点（装配链回流环上的第三站：把子智能体包成 SequentialAgent）
 * <p>
 * 功能：用上下文里当前这条工作流配置的 subAgents 从 agentGroup 取子智能体，
 * 造一个 SequentialAgent 并登记回 agentGroup，随后回流到 AgentWorkflowNode 处理下一条。
 * <pre>
 *   doApply()  造 SequentialAgent(name/description/subAgents) --> agentGroup.put --> 路由
 *   get()      返回 getBean("agentWorkflowNode")，把控制权交回 AgentWorkflowNode（回流）
 * </pre>
 * 运行过程（回流环，本节点是其中一环）：
 * <pre>
 *   AgentWorkflowNode.get(): type="sequential" --> sequentialAgentNode
 *        |
 *        v
 *   doApply(requestParameter, dynamicContext)
 *        |
 *        | (1) currentAgentWorkflow = dynamicContext.getCurrentAgentWorkflow()
 *        |
 *        | (2) subAgents = dynamicContext.queryAgentList(currentAgentWorkflow.getSubAgents())
 *        |     （子体既可以是 module.agents 的原子智能体，也可以是先前已装配好的
 *        |       loop / parallel / sequential 复合体的名字）
 *        |
 *        | (3) SequentialAgent.builder()
 *        |        .name(currentAgentWorkflow.getName())
 *        |        .description(currentAgentWorkflow.getDescription())
 *        |        .subAgents(subAgents)
 *        |
 *        | (4) agentGroup.put(currentAgentWorkflow.getName(), sequentialAgent)
 *        v
 *   get() --> getBean("agentWorkflowNode") --> AgentWorkflowNode 处理下一条工作流
 * </pre>
 * 案例（test-agent.yml 里的三段式流水线，仓库中真实存在）：
 * <pre>
 *   输入: currentAgentWorkflow = {type="sequential", name="CodePipelineAgent",
 *                                subAgents=["CodeWriterAgent","CodeReviewerAgent","CodeRefactorerAgent"]}
 *         agentGroup 里已有这三个 LlmAgent
 *   处理: queryAgentList 按序取回 3 个子体 --> SequentialAgent("CodePipelineAgent", subAgents=[三个])
 *   输出: 无返回值；agentGroup 多出 "CodePipelineAgent" -> SequentialAgent
 *         配置里 module.runner.agent-name = CodePipelineAgent，最终它会被 RunnerNode 当作根智能体
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>子体顺序即 subAgents 的顺序（queryAgentList 按 List 顺序逐个收集）：
 *       串行结构的执行次序完全由配置数组决定，调整顺序不用改代码。</li>
 *   <li>回流靠 getBean("agentWorkflowNode")：本类虽然注入了 RunnerNode 字段，
 *       但 get() 返回的是从容器取回的 AgentWorkflowNode ——
 *       链尾选择权始终在 AgentWorkflowNode 手里，该字段不参与装配路径。</li>
 *   <li>本节点不校验子体数量：subAgents 为空、或名字全取不到时，会造出一个没有子体的
 *       SequentialAgent 并登记进 agentGroup，装配阶段不会报错。</li>
 *   <li>复合体可嵌套：先配置的 loop / parallel 已经进了 agentGroup，
 *       后面的 sequential 就能按名字把它们当子体用，这是「工作流套工作流」的实现方式。</li>
 * </ul>
 */
@Slf4j
@Service("sequentialAgentNode")
public class SequentialAgentNode extends AbstractArmorySupport {

    @Resource
    private RunnerNode runnerNode;

    /** 执行当前节点的智能体配置装配。 */
    @Override
    protected AiAgentRegisterVO doApply(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        log.info("Ai Agent 装配操作 - SequentialAgentNode");

        AiAgentConfigTableVO.Module.AgentWorkflow currentAgentWorkflow = dynamicContext.getCurrentAgentWorkflow();

        List<String> subAgentNames = currentAgentWorkflow.getSubAgents();
        List<BaseAgent> subAgents = dynamicContext.queryAgentList(subAgentNames);

        SequentialAgent sequentialAgent =
                SequentialAgent.builder()
                        .name(currentAgentWorkflow.getName())
                        .description(currentAgentWorkflow.getDescription())
                        .subAgents(subAgents)
                        .build();

        dynamicContext.getAgentGroup().put(currentAgentWorkflow.getName(), sequentialAgent);

        return router(requestParameter, dynamicContext);
    }

    /** 返回当前装配节点完成后应执行的后续策略。 */
    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, AiAgentRegisterVO> get(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        return getBean("agentWorkflowNode");
    }

}

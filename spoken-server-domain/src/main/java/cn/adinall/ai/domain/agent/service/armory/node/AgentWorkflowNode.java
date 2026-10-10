package cn.adinall.ai.domain.agent.service.armory.node;

import cn.adinall.ai.domain.agent.model.entity.ArmoryCommandEntity;
import cn.adinall.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import cn.adinall.ai.domain.agent.model.valobj.AiAgentRegisterVO;
import cn.adinall.ai.domain.agent.model.valobj.enums.AgentTypeEnum;
import cn.adinall.ai.domain.agent.service.armory.AbstractArmorySupport;
import cn.adinall.ai.domain.agent.service.armory.factory.DefaultArmoryFactory;
import cn.adinall.ai.domain.agent.service.armory.node.workflow.LoopAgentNode;
import cn.adinall.ai.domain.agent.service.armory.node.workflow.ParallelAgentNode;
import cn.adinall.ai.domain.agent.service.armory.node.workflow.SequentialAgentNode;
import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;

/**
 * 工作流装配节点（装配链第 5 环，也是唯一会「回流」的节点）
 * <p>
 * 功能：按 module.agentWorkflows 逐条把已创建的原子智能体组装成 Loop / Parallel / Sequential 结构，
 * 每处理一条就自增步号并回流到本节点，直到配置处理完才交给 RunnerNode。
 * <pre>
 *   doApply()  取第 currentStepIndex 条工作流配置放到上下文，步号 +1，再路由
 *   get()      按当前工作流类型返回对应节点；没有待处理配置时返回 RunnerNode
 * </pre>
 * 运行过程（回流，用 2 条工作流配置举例）：
 * <pre>
 *   AgentNode.get() --> AgentWorkflowNode   (currentStepIndex=0)
 *        |
 *        v
 *   doApply(): agentWorkflows.size()=2 > currentStepIndex=0
 *        |     currentAgentWorkflow = agentWorkflows.get(0)
 *        |     addCurrentStepIndex()  --> currentStepIndex=1
 *        v
 *   get(): type="sequential" --> sequentialAgentNode
 *        |
 *        v
 *   SequentialAgentNode.doApply(): 造 SequentialAgent --> agentGroup.put(name, agent)
 *        |
 *        v
 *   SequentialAgentNode.get() --> getBean("agentWorkflowNode") --> 回到 AgentWorkflowNode
 *        |
 *        | 第二轮: currentStepIndex=1 < 2 --> 处理 agentWorkflows.get(1) --> currentStepIndex=2
 *        |          --> loopAgentNode / parallelAgentNode / sequentialAgentNode --> 再回流
 *        |
 *        v
 *   第三轮: currentStepIndex=2 >= size=2
 *        |     setCurrentAgentWorkflow(null)
 *        v
 *   get(): currentAgentWorkflow == null --> runnerNode
 *        |
 *        v
 *   RunnerNode.doApply(): 造 InMemoryRunner + registerBean(agentId, ...)
 * </pre>
 * 案例：
 * <pre>
 *   输入: module.agentWorkflows = [
 *           {type="sequential", name="pipeline", subAgents=["main-agent","judge-agent"]},
 *           {type="loop",       name="retry",    subAgents=["judge-agent"], maxIterations=3}]
 *         上下文里已有 agentGroup={main-agent, judge-agent}
 *   处理: 第 1 轮造 SequentialAgent("pipeline") 并入 group；
 *         第 2 轮造 LoopAgent("retry") 并入 group；
 *         第 3 轮 currentAgentWorkflow=null --> runnerNode
 *   输出: 无返回值；agentGroup 里多出 pipeline / retry 两个复合智能体
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>本节点同时承担「遍历器」和「路由器」两个角色：doApply 决定「处理哪一条」，
 *       get 决定「交给哪个节点处理」，因此不需要为工作流新建循环节点。</li>
 *   <li>type 到节点的映射走 AgentTypeEnum.formType(type) --> node 名字，
 *       type 写错（不在 loop/parallel/sequential 里）会抛 RuntimeException("agentWorkflow type is error!")，
 *       属于配置错误早失败。</li>
 *   <li>子智能体按 subAgents 里的名字从 agentGroup 取（DynamicContext.queryAgentList）：
 *       名字不存在时该元素被静默跳过，所以 subAgents 拼错不会报错，只会少挂一个子体。</li>
 *   <li>子节点靠 getBean("agentWorkflowNode") 回流，形成装配链内部的小循环；
 *       顺序由配置数组顺序决定，先配置的复合体可以被后面的复合体当子体引用。</li>
 * </ul>
 */
@Slf4j
@Service
public class AgentWorkflowNode extends AbstractArmorySupport {

    @Resource
    private LoopAgentNode loopAgentNode;
    @Resource
    private ParallelAgentNode parallelAgentNode;
    @Resource
    private SequentialAgentNode sequentialAgentNode;
    @Resource
    private RunnerNode runnerNode;

    @Override
    protected AiAgentRegisterVO doApply(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        log.info("Ai Agent 装配操作 - AgentWorkflowNode");

        AiAgentConfigTableVO aiAgentConfigTableVO = requestParameter.getAiAgentConfigTableVO();
        List<AiAgentConfigTableVO.Module.AgentWorkflow> agentWorkflows = aiAgentConfigTableVO.getModule().getAgentWorkflows();

        if (null == agentWorkflows || agentWorkflows.isEmpty() || dynamicContext.getCurrentStepIndex() >= agentWorkflows.size()) {
            // 设置结果值
            dynamicContext.setCurrentAgentWorkflow(null);
            // 路由下节点
            return router(requestParameter, dynamicContext);
        }

        dynamicContext.setCurrentAgentWorkflow(agentWorkflows.get(dynamicContext.getCurrentStepIndex()));

        // 步骤值增加
        dynamicContext.addCurrentStepIndex();

        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, AiAgentRegisterVO> get(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {

        AiAgentConfigTableVO.Module.AgentWorkflow currentAgentWorkflow = dynamicContext.getCurrentAgentWorkflow();

        if (null == currentAgentWorkflow){
            return runnerNode;
        }

        String type = currentAgentWorkflow.getType();
        AgentTypeEnum agentTypeEnum = AgentTypeEnum.formType(type);

        if (null == agentTypeEnum){
            throw new RuntimeException("agentWorkflow type is error!");
        }

        String node = agentTypeEnum.getNode();

        return switch (node){
            case "loopAgentNode" -> loopAgentNode;
            case "parallelAgentNode" -> parallelAgentNode;
            case "sequentialAgentNode" -> sequentialAgentNode;
            default -> runnerNode;
        };
    }

}

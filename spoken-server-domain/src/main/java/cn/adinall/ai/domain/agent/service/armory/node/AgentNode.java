package cn.adinall.ai.domain.agent.service.armory.node;

import cn.adinall.ai.domain.agent.model.entity.ArmoryCommandEntity;
import cn.adinall.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import cn.adinall.ai.domain.agent.model.valobj.AiAgentRegisterVO;
import cn.adinall.ai.domain.agent.service.armory.AbstractArmorySupport;
import cn.adinall.ai.domain.agent.service.armory.factory.DefaultArmoryFactory;
import cn.adinall.ai.domain.agent.service.armory.matter.patch.MySpringAI;
import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.google.adk.agents.LlmAgent;
import com.google.adk.models.springai.SpringAI;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;

/**
 * 智能体节点（装配链第 4 环：把每个 agent 配置变成一个 LlmAgent）
 * <p>
 * 功能：遍历 module.agents，用上一步造好的 ChatModel 逐只创建 LlmAgent（名字/描述/指令/outputKey），
 * 以 agentName 为键放进上下文的 agentGroup，供后面的工作流节点按名字组装。
 * <pre>
 *   doApply()  批量创建 LlmAgent --> agentGroup.put(name, llmAgent) --> 路由下一节点
 *   get()      返回下一个节点 AgentWorkflowNode
 * </pre>
 * 运行过程：
 * <pre>
 *   ChatModelNode.get() --> AgentNode
 *        |
 *        v
 *   doApply(requestParameter, dynamicContext)
 *        |
 *        | (1) chatModel = dynamicContext.getChatModel()
 *        |
 *        | (2) for agentConfig in module.agents:
 *        |        |
 *        |        v
 *        |     LlmAgent.builder()
 *        |          .name(agentConfig.name)
 *        |          .description(agentConfig.description)
 *        |          .model(new MySpringAI(chatModel))   把 Spring AI 的模型包成 ADK 的 BaseLlm
 *        |          .instruction(agentConfig.instruction)
 *        |          .outputKey(agentConfig.outputKey)
 *        |        |
 *        |        v
 *        |     dynamicContext.agentGroup.put(agentConfig.name, llmAgent)
 *        v
 *   router(...) --> AgentWorkflowNode（按 subAgents 名字从 agentGroup 取）
 * </pre>
 * 案例：
 * <pre>
 *   输入: module.agents=[{name="main-agent", instruction="你是口语教练",
 *                        description="主对话体", outputKey="reply"}]
 *   处理: 用上下文里的 ChatModel 包成 MySpringAI，造出 LlmAgent
 *   输出: 无返回值；dynamicContext.getAgentGroup() = {"main-agent": LlmAgent}
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>agentGroup 以配置文件里的 name 为键：工作流节点的 subAgents 写的就是这个名字，
 *       两处靠字符串对齐，改名要同时改。</li>
 *   <li>这里只创建「原子智能体」，不创建循环/并行/串行结构 —— 结构由 AgentWorkflowNode
 *       按 module.agentWorkflows 组装，职责分开。</li>
 *   <li>LlmAgent 的模型走 MySpringAI 适配：ADK 只认 BaseLlm，Spring AI 的 ChatModel
 *       必须包一层，顺带在适配层里处理消息转换。</li>
 * </ul>
 */
@Slf4j
@Service
public class AgentNode extends AbstractArmorySupport {

    @Resource
    private AgentWorkflowNode agentWorkflowNode;

    @Override
    protected AiAgentRegisterVO doApply(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        log.info("Ai Agent 装配操作 - AgentNode");

        ChatModel chatModel = dynamicContext.getChatModel();

        AiAgentConfigTableVO aiAgentConfigTableVO = requestParameter.getAiAgentConfigTableVO();
        List<AiAgentConfigTableVO.Module.Agent> agents = aiAgentConfigTableVO.getModule().getAgents();

        for (AiAgentConfigTableVO.Module.Agent agentConfig : agents) {
            LlmAgent llmAgent = LlmAgent.builder()
                    .name(agentConfig.getName())
                    .description(agentConfig.getDescription())
                    .model(new MySpringAI(chatModel))
                    .instruction(agentConfig.getInstruction())
                    .outputKey(agentConfig.getOutputKey())
                    .build();

            dynamicContext.getAgentGroup().put(agentConfig.getName(), llmAgent);
        }

        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, AiAgentRegisterVO> get(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        return agentWorkflowNode;
    }

}

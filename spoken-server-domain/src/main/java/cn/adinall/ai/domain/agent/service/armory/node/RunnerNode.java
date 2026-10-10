package cn.adinall.ai.domain.agent.service.armory.node;

import cn.adinall.ai.domain.agent.model.entity.ArmoryCommandEntity;
import cn.adinall.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import cn.adinall.ai.domain.agent.model.valobj.AiAgentRegisterVO;
import cn.adinall.ai.domain.agent.service.armory.AbstractArmorySupport;
import cn.adinall.ai.domain.agent.service.armory.factory.DefaultArmoryFactory;
import cn.adinall.ai.types.enums.ResponseCode;
import cn.adinall.ai.types.exception.AppException;
import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.SequentialAgent;
import com.google.adk.plugins.BasePlugin;
import com.google.adk.runner.InMemoryRunner;
import com.google.common.collect.ImmutableList;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 执行节点（装配链的最后一环：产出可运行的 Runner 并注册成 Spring bean）
 * <p>
 * 功能：把前面节点攒在上下文里的组建成果组装成一个 InMemoryRunner（用哪个智能体当根、挂哪些插件），
 * 连同 appName / agentId / agentName / agentDesc 一起注册进 Spring 容器，供运行期按 agentId 取用。
 * <pre>
 *   doApply()   造 InMemoryRunner --> 组装 AiAgentRegisterVO --> registerBean(agentId, ...)
 *   getRunner() 从 module.runner 取 agentName/pluginNameList，造 InMemoryRunner
 *   get()       返回 defaultStrategyHandler（链条到此结束）
 * </pre>
 * 运行过程：
 * <pre>
 *   AgentWorkflowNode.get() 返回 RunnerNode（currentAgentWorkflow 为 null 时）
 *        |
 *        v
 *   doApply(requestParameter, dynamicContext)
 *        |
 *        | (1) 读配置: appName / agent.{agentId, agentName, agentDesc}
 *        |
 *        | (2) getRunner(dynamicContext, aiAgentConfigTableVO, appName)
 *        |        |
 *        |        +--> module.runner.agentName 为空 --> 抛 ILLEGAL_PARAMETER（runner.agentName is null）
 *        |        +--> dynamicContext.agentGroup.get(agentName) --> BaseAgent（根智能体）
 *        |        +--> module.runner.pluginNameList 逐个 getBean(name) --> List&lt;BasePlugin&gt;
 *        |        |        （列表为空时用 ImmutableList.of() 占位，不传 null）
 *        |        v
 *        |     new InMemoryRunner(baseAgent, appName, plugins)
 *        |
 *        | (3) AiAgentRegisterVO.builder().appName().agentId().agentName().agentDesc().runner()
 *        |        |
 *        |        v
 *        |     registerBean(agentId, AiAgentRegisterVO.class, vo)   <-- @Slf4j 日志: 成功注册Bean
 *        v
 *   get() --> defaultStrategyHandler（null，链结束，ArmoryService 的遍历进入下一个配置表）
 * </pre>
 * 案例：
 * <pre>
 *   输入: appName="demo-app", agent={agentId="3001", agentName="对话助手", agentDesc="口语陪练"},
 *         module.runner={agentName="pipeline", pluginNameList=["myLogPlugin"]}
 *   处理: agentGroup 里取到 pipeline（SequentialAgent）作为根；插件按名字从容器取 myLogPlugin
 *   输出: 无返回值；Spring 容器注册 bean "3001" -> AiAgentRegisterVO{appName="demo-app",
 *         agentId="3001", runner=InMemoryRunner(pipeline, ["myLogPlugin"])}
 *         失败时抛 AppException(ILLEGAL_PARAMETER)：runner.agentName 为空
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>Runner 在装配期 new 好并随 bean 常驻：一次装配、多次对话（ChatService 复用同一个 runner），
 *       避免每个请求重建智能体树。</li>
 *   <li>根智能体由 <b>名字</b> 指定（module.runner.agentName），因此它必须指向
 *       module.agents / module.agentWorkflows 里已经造好的名字，否则 agentGroup.get 返回 null，
 *       InMemoryRunner 会在后续对话时出错。</li>
 *   <li>插件名同样按名字从容器取，写错会在装配期直接抛 NoSuchBeanDefinitionException，
 *       而不是等到第一次对话才发现插件没生效。</li>
 *   <li>agentName 为空时立刻抛错：此时链条后面没有任何兜底节点，早失败好过注册一个
 *       永远跑不起来的 bean。</li>
 * </ul>
 *
 * @author 魏玉石
 * 2025/12/29 16:09
 */
@Slf4j
@Service
public class RunnerNode extends AbstractArmorySupport {

    protected AiAgentRegisterVO doApply(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        log.info("Ai Agent 装配操作 - RunnerNode");

        AiAgentConfigTableVO aiAgentConfigTableVO = requestParameter.getAiAgentConfigTableVO();
        String appName = aiAgentConfigTableVO.getAppName();
        AiAgentConfigTableVO.Agent agent = aiAgentConfigTableVO.getAgent();
        String agentId = agent.getAgentId();
        String agentName = agent.getAgentName();
        String agentDesc = agent.getAgentDesc();

        InMemoryRunner runner = getRunner(dynamicContext, aiAgentConfigTableVO, appName);

        AiAgentRegisterVO aiAgentRegisterVO = AiAgentRegisterVO.builder()
                .appName(appName)
                .agentId(agentId)
                .agentName(agentName)
                .agentDesc(agentDesc)
                .runner(runner)
                .build();

        // 注册到 Spring 容器
        registerBean(agentId, AiAgentRegisterVO.class, aiAgentRegisterVO);

        return aiAgentRegisterVO;
    }

    private InMemoryRunner getRunner(DefaultArmoryFactory.DynamicContext dynamicContext, AiAgentConfigTableVO aiAgentConfigTableVO, String appName) {
        AiAgentConfigTableVO.Module.Runner runnerConfig = aiAgentConfigTableVO.getModule().getRunner();

        String agentName = runnerConfig.getAgentName();
        if (StringUtils.isBlank(agentName)) {
            log.error("runner.agentName is null");
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), ResponseCode.ILLEGAL_PARAMETER.getInfo());
        }

        BaseAgent baseAgent = dynamicContext.getAgentGroup().get(agentName);

        List<BasePlugin> plugins;
        List<String> pluginNameList = runnerConfig.getPluginNameList();
        if (null != pluginNameList && !pluginNameList.isEmpty()) {
            plugins = new ArrayList<>();
            for (String pluginName : pluginNameList) {
                BasePlugin plugin = getBean(pluginName);
                plugins.add(plugin);
            }
        } else {
            plugins = ImmutableList.of();
        }

        return new InMemoryRunner(baseAgent, appName, plugins);
    }

    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, AiAgentRegisterVO> get(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        return defaultStrategyHandler;
    }


}

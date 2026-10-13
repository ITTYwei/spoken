package cn.adinall.ai.domain.agent.service.armory.node;

import cn.adinall.ai.domain.agent.model.entity.ArmoryCommandEntity;
import cn.adinall.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import cn.adinall.ai.domain.agent.model.valobj.AiAgentRegisterVO;
import cn.adinall.ai.domain.agent.service.armory.AbstractArmorySupport;
import cn.adinall.ai.domain.agent.service.armory.factory.DefaultArmoryFactory;
import cn.adinall.ai.domain.agent.service.armory.matter.mcp.client.TooMcpCreateService;
import cn.adinall.ai.domain.agent.service.armory.matter.mcp.client.factory.DefaultMcpClientFactory;
import cn.adinall.ai.domain.agent.service.armory.matter.skills.ToolSkillsCreateService;
import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;

/**
 * 对话模型节点（装配链第 3 环：造 ChatModel，并把它能看到的所有工具挂上去）
 * <p>
 * 功能：把上下文里的 OpenAiApi + 配置里的 model 组装成 OpenAiChatModel，
 * 同时把 MCP 工具和 Skills 工具转成 ToolCallback 列表绑到模型默认参数上。
 * <pre>
 *   doApply()  造工具回调 --> 造 OpenAiChatModel --> dynamicContext.setChatModel()
 *   get()      返回下一个节点 AgentNode
 * </pre>
 * 运行过程：
 * <pre>
 *   AiApiNode.get() --> ChatModelNode
 *        |
 *        v
 *   doApply(requestParameter, dynamicContext)
 *        |
 *        | (1) openAiApi = dynamicContext.getOpenAiApi()
 *        |
 *        | (2) 装配 MCP 工具: for toolMcp in module.chatModel.toolMcpList
 *        |        |
 *        |        v
 *        |     defaultMcpClientFactory.getTooMcpCreateService(toolMcp)
 *        |        |        local 非空 --> LocalToolMcpCreateService
 *        |        |        sse   非空 --> SSEToolMcpCreateService
 *        |        |        stdio 非空 --> StdioToolMcpCreateService
 *        |        |        都不匹配   --> 抛 NOT_FOUND_METHOD
 *        |        v
 *        |     buildToolCallback(toolMcp) --> ToolCallback[] 累加进 toolCallbackList
 *        |
 *        | (3) 装配 Skills 工具: for toolSkills in module.chatModel.toolSkillsList
 *        |        |
 *        |        v
 *        |     toolSkillsCreateService.buildToolCallback(toolSkills) --> 累加
 *        |
 *        | (4) OpenAiChatModel.builder()
 *        |        .openAiApi(openAiApi)
 *        |        .defaultOptions(OpenAiChatOptions.model(...).toolCallbacks(toolCallbackList))
 *        |            +-- module.chatModel.reasoningEffort 非空时再挂一个 reasoningEffort（如 none 关掉思考）
 *        |        v
 *        |     dynamicContext.setChatModel(chatModel)
 *        v
 *   router(...) --> AgentNode（用这个 ChatModel 造 LlmAgent）
 * </pre>
 * 案例：
 * <pre>
 *   输入: module.chatModel{model="deepseek-chat",
 *                           toolMcpList=[{local={name="local-tools"}}],
 *                           toolSkillsList=[{type="directory", path="/skills"}]}
 *   处理: local 非空 --> LocalToolMcpCreateService 造出本机工具回调；
 *         Skills 服务按 path 扫出技能回调；两者合并进 OpenAiChatOptions
 *   输出: 无返回值；dynamicContext.getChatModel() 得到一个带全部工具回调的 OpenAiChatModel
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>工具在装配期一次性绑到模型默认参数上：运行期（ChatService.handleMessage）不用再传工具，
 *       调用方拿到的 ChatModel 已经是「带工具的」。</li>
 *   <li>MCP 用工厂 + 三种参数形态（local/sse/stdio）选实现，新增接入方式只需加一个
 *       TooMcpCreateService 实现并在工厂里加一个分支，节点本身不用改。</li>
 *   <li>toolMcp 三种参数都不匹配时直接抛 NOT_FOUND_METHOD：配置写错在启动期就暴露，
 *       不会造出一个「没有工具但看起来正常」的智能体。</li>
 *   <li>MCP / Skills 的回调都平铺进同一个列表：对模型来说工具来源无差别，只看回调名字。</li>
 *   <li>reasoningEffort 挂在模型默认参数上而不是每轮请求上：思考开关是「这个模型怎么用」的部署决策，
 *       运行期（ChatService.handleTextStream）拿到的 Prompt 不带 options，靠默认参数兜底；
 *       留空不下发，保证换回非推理模型时这里不需要改代码。</li>
 * </ul>
 */
@Slf4j
@Service
public class ChatModelNode extends AbstractArmorySupport {

    @Resource
    private AgentNode agentNode;

    @Resource
    private DefaultMcpClientFactory defaultMcpClientFactory;

    @Resource
    private ToolSkillsCreateService toolSkillsCreateService;

    @Override
    protected AiAgentRegisterVO doApply(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        log.info("Ai Agent 装配操作 - ChatModelNode");

        // 获取上下文对象
        OpenAiApi openAiApi = dynamicContext.getOpenAiApi();

        // 获取配置对象
        AiAgentConfigTableVO aiAgentConfigTableVO = requestParameter.getAiAgentConfigTableVO();
        AiAgentConfigTableVO.Module.ChatModel chatModelConfig = aiAgentConfigTableVO.getModule().getChatModel();
        List<AiAgentConfigTableVO.Module.ChatModel.ToolMcp> toolMcpList = chatModelConfig.getToolMcpList();
        List<AiAgentConfigTableVO.Module.ChatModel.ToolSkills> toolSkillsList = chatModelConfig.getToolSkillsList();

        // 构建mcp服务（工厂）
        List<ToolCallback> toolCallbackList = new ArrayList<>();

        if (null != toolMcpList && !toolMcpList.isEmpty()) {
            for (AiAgentConfigTableVO.Module.ChatModel.ToolMcp toolMcp : toolMcpList) {
                TooMcpCreateService tooMcpCreateService = defaultMcpClientFactory.getTooMcpCreateService(toolMcp);
                ToolCallback[] toolCallbacks = tooMcpCreateService.buildToolCallback(toolMcp);
                toolCallbackList.addAll(List.of(toolCallbacks));
            }
        }

        // 构建skills服务
        if (null != toolSkillsList && !toolSkillsList.isEmpty()) {
            for (AiAgentConfigTableVO.Module.ChatModel.ToolSkills toolSkills : toolSkillsList) {
                ToolCallback[] toolCallbacks = toolSkillsCreateService.buildToolCallback(toolSkills);
                toolCallbackList.addAll(List.of(toolCallbacks));
            }
        }

        // 构建对话模型
        OpenAiChatOptions.Builder defaultOptions = OpenAiChatOptions.builder()
                .model(chatModelConfig.getModel())
                .toolCallbacks(toolCallbackList);

        // 推理强度留空就不下发这个字段：不发时行为与改造前完全一致，别的模型/服务端不会被它影响。
        String reasoningEffort = chatModelConfig.getReasoningEffort();
        if (null != reasoningEffort && !reasoningEffort.isBlank()) {
            defaultOptions.reasoningEffort(reasoningEffort);
        }

        ChatModel chatModel = OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(defaultOptions.build())
                .build();

        dynamicContext.setChatModel(chatModel);

        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, AiAgentRegisterVO> get(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        return agentNode;
    }

}

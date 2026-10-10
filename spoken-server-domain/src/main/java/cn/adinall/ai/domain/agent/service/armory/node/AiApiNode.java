package cn.adinall.ai.domain.agent.service.armory.node;

import cn.adinall.ai.domain.agent.model.entity.ArmoryCommandEntity;
import cn.adinall.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import cn.adinall.ai.domain.agent.model.valobj.AiAgentRegisterVO;
import cn.adinall.ai.domain.agent.service.armory.AbstractArmorySupport;
import cn.adinall.ai.domain.agent.service.armory.factory.DefaultArmoryFactory;
import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

/**
 * 模型接入节点（装配链第 2 环：把配置里的 baseUrl/apiKey 变成 OpenAiApi）
 * <p>
 * 功能：读 module.aiApi 配置，构造一个 OpenAI 兼容的 OpenAiApi，写入上下文给后续节点复用。
 * <pre>
 *   doApply()  构造 OpenAiApi --> dynamicContext.setOpenAiApi() --> 路由下一节点
 *   get()      返回下一个节点 ChatModelNode
 * </pre>
 * 运行过程：
 * <pre>
 *   RootNode.get() --> AiApiNode
 *        |
 *        v
 *   doApply(requestParameter, dynamicContext)
 *        |
 *        | (1) 读配置: module.aiApi{baseUrl, apiKey, completionsPath, embeddingsPath}
 *        |        |
 *        |        v
 *        |     路径为空则兜底: "v1/chat/completions" / "v1/embeddings"
 *        |
 *        | (2) OpenAiApi.builder().baseUrl(..).apiKey(..)...
 *        |        |
 *        |        v
 *        |     dynamicContext.setOpenAiApi(openAiApi)
 *        |
 *        v
 *   router(...) --> ChatModelNode（用上下文里的 OpenAiApi 造 ChatModel）
 * </pre>
 * 案例：
 * <pre>
 *   输入: module.aiApi{baseUrl="https://api.deepseek.com", apiKey="sk-***",
 *                      completionsPath=null, embeddingsPath=null}
 *   处理: completionsPath 空 --> "v1/chat/completions"；embeddingsPath 空 --> "v1/embeddings"
 *   输出: 无返回值；dynamicContext.getOpenAiApi() 得到配好的 OpenAiApi
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>OpenAiApi 在装配期构造一次并放进上下文：它只是「地址 + 密钥 + 路径」的载体，
 *       后续 ChatModelNode 直接取用，避免每个节点各自重复读配置。</li>
 *   <li>completions/embeddings 路径给默认值：多数 OpenAI 兼容服务走标准路径，
 *       配置里不写也能直接用；写了则以配置为准。</li>
 *   <li>不覆写 multiThread：装配链中本节点没有可并行的子任务，串行即正确顺序。</li>
 * </ul>
 */
@Slf4j
@Service
public class AiApiNode extends AbstractArmorySupport {

    @Resource
    private ChatModelNode chatModelNode;

    @Override
    protected AiAgentRegisterVO doApply(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        log.info("Ai Agent 装配操作 - AiApiNode");

        AiAgentConfigTableVO aiAgentConfigTableVO = requestParameter.getAiAgentConfigTableVO();
        AiAgentConfigTableVO.Module.AiApi aiApiConfig = aiAgentConfigTableVO.getModule().getAiApi();

        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl(aiApiConfig.getBaseUrl())
                .apiKey(aiApiConfig.getApiKey())
                .completionsPath(StringUtils.isNotBlank(aiApiConfig.getCompletionsPath()) ? aiApiConfig.getCompletionsPath() : "v1/chat/completions")
                .embeddingsPath(StringUtils.isNotBlank(aiApiConfig.getEmbeddingsPath()) ? aiApiConfig.getEmbeddingsPath() : "v1/embeddings")
                .build();

        dynamicContext.setOpenAiApi(openAiApi);

        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, AiAgentRegisterVO> get(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        return chatModelNode;
    }

}

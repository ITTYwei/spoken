package cn.adinall.ai.infrastructure.gateway.agent.armory.node;

import cn.adinall.ai.infrastructure.gateway.agent.model.ArmoryCommandEntity;
import cn.adinall.ai.infrastructure.config.agent.AiAgentConfigTableVO;
import cn.adinall.ai.infrastructure.gateway.agent.armory.factory.DefaultArmoryFactory;
import org.junit.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;

import java.lang.reflect.Field;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;

/**
 * ChatModelNode 的「模型默认参数」契约测试。
 * <p>
 * 功能：验证配置里的 {@code module.chat-model.reasoning-effort} 被原样挂到
 * {@link OpenAiChatOptions} 的默认参数上，且留空时不下发该字段。
 * <pre>
 *   AiAgentConfigTableVO(chat-model.reasoning-effort) -> ChatModelNode.doApply -> ChatModel.getDefaultOptions().reasoningEffort
 * </pre>
 * 案例：
 * <pre>
 *   输入: reasoning-effort=none  -> 输出: 默认参数 reasoningEffort="none"
 *   输入: 不配 / 配空白          -> 输出: 默认参数 reasoningEffort=null（请求体里不会出现 reasoning_effort）
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>只跑装配节点的参数组装，不连模型也不连网：OpenAiApi 指向一个不会被访问的地址。</li>
 *   <li>为什么值得测：这条链路决定了「模型会不会先思考 16~70 秒再出字」。配置写了但没挂上去
 *       （例如字段名漂移、null 被序列化成 "null"）在本项目里等于数字人长时间无话可说，
 *       属于只能用端到端才看得出来的故障，所以在这里钉住。</li>
 *   <li>后继节点用 Mockito 顶替：本测试只关心 ChatModel，router 只要求 get() 返回一个 handler。</li>
 * </ul>
 */
public class ChatModelNodeTest {

    /** 配了 reasoning-effort：必须原样进默认参数。 */
    @Test
    public void reasoningEffortGoesIntoModelDefaults() throws Exception {
        assertEquals("none", defaultOptions("none").getReasoningEffort());
    }

    /** 没配 / 配空白：不能凭空多出一个 reasoning_effort，避免影响别的模型与服务端。 */
    @Test
    public void blankReasoningEffortIsNotSent() throws Exception {
        assertNull(defaultOptions(null).getReasoningEffort());
        assertNull(defaultOptions("   ").getReasoningEffort());
    }

    /** 跑一次装配节点，取回模型默认参数。 */
    private static OpenAiChatOptions defaultOptions(String reasoningEffort) throws Exception {
        AiAgentConfigTableVO.Module.ChatModel chatModelConfig = new AiAgentConfigTableVO.Module.ChatModel();
        chatModelConfig.setModel("bonsai-2-27b");
        chatModelConfig.setReasoningEffort(reasoningEffort);

        AiAgentConfigTableVO.Module module = new AiAgentConfigTableVO.Module();
        module.setChatModel(chatModelConfig);

        AiAgentConfigTableVO table = new AiAgentConfigTableVO();
        table.setAppName("spoken-server");
        table.setModule(module);

        ChatModelNode node = new ChatModelNode();
        Field next = ChatModelNode.class.getDeclaredField("agentNode");
        next.setAccessible(true);
        next.set(node, mock(AgentNode.class));

        DefaultArmoryFactory.DynamicContext context = new DefaultArmoryFactory.DynamicContext();
        context.setOpenAiApi(OpenAiApi.builder().baseUrl("http://127.0.0.1:9").apiKey("test-key-only").build());

        node.doApply(ArmoryCommandEntity.builder().aiAgentConfigTableVO(table).build(), context);

        ChatModel chatModel = context.getChatModel();
        return (OpenAiChatOptions) chatModel.getDefaultOptions();
    }
}

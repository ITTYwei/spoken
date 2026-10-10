package cn.adinall.ai.config;

import cn.adinall.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import org.junit.Assert;
import org.junit.Test;

import java.util.Map;

/**
 * 启动日志脱敏的防回归测试。
 * <p>
 * 背景：{@link AiAgentAutoConfig} 曾经把整个配置对象
 * （含 module.aiApi.apiKey、toolMcpList[].sse.sseEndpoint 里的 api_key）
 * 用 JSON.toJSONString 打进日志，导致明文密钥落盘泄露。
 * 本测试锁死「摘要里绝不出现密钥明文」这条约束。
 */
public class AiAgentAutoConfigLogTest {

    private static final String FAKE_LLM_KEY = "sk-FAKE-FOR-TEST-ONLY-DO-NOT-USE-0000";
    private static final String FAKE_BAIDU_KEY = "bce-v3/ALTAK-FAKE-FOR-TEST-ONLY/DO-NOT-USE-0000";

    private static AiAgentConfigTableVO buildTable() {
        AiAgentConfigTableVO.Module.AiApi aiApi = new AiAgentConfigTableVO.Module.AiApi();
        aiApi.setBaseUrl("https://apis.example.cn");
        aiApi.setApiKey(FAKE_LLM_KEY);

        AiAgentConfigTableVO.Module.ChatModel.ToolMcp.SSEServerParameters sse =
                new AiAgentConfigTableVO.Module.ChatModel.ToolMcp.SSEServerParameters();
        sse.setName("baidu-search");
        sse.setBaseUri("http://appbuilder.baidu.com/v2/ai_search/mcp/");
        sse.setSseEndpoint("sse?api_key=" + FAKE_BAIDU_KEY);

        AiAgentConfigTableVO.Module.ChatModel.ToolMcp toolMcp = new AiAgentConfigTableVO.Module.ChatModel.ToolMcp();
        toolMcp.setSse(sse);

        AiAgentConfigTableVO.Module.ChatModel chatModel = new AiAgentConfigTableVO.Module.ChatModel();
        chatModel.setModel("gpt-4.1");
        chatModel.setToolMcpList(java.util.Collections.singletonList(toolMcp));

        AiAgentConfigTableVO.Module.Runner runner = new AiAgentConfigTableVO.Module.Runner();
        runner.setAgentName("spokenCoach");

        AiAgentConfigTableVO.Module module = new AiAgentConfigTableVO.Module();
        module.setAiApi(aiApi);
        module.setChatModel(chatModel);
        module.setRunner(runner);

        AiAgentConfigTableVO.Agent agent = new AiAgentConfigTableVO.Agent();
        agent.setAgentId("100003");
        agent.setAgentName("口语教练");

        AiAgentConfigTableVO table = new AiAgentConfigTableVO();
        table.setAppName("spoken-server");
        table.setAgent(agent);
        table.setModule(module);
        return table;
    }

    /** 摘要里不得出现任何密钥明文。 */
    @Test
    public void summarizeMustNotLeakSecrets() {
        String summary = AiAgentAutoConfig.summarize(Map.of("spokenServer", buildTable()));

        Assert.assertFalse("摘要泄露了 LLM 密钥", summary.contains(FAKE_LLM_KEY));
        Assert.assertFalse("摘要泄露了百度密钥", summary.contains(FAKE_BAIDU_KEY));
        Assert.assertFalse("摘要泄露了 sseEndpoint 明文", summary.contains("api_key=" + FAKE_BAIDU_KEY));
        Assert.assertFalse("摘要疑似整对象序列化（出现了 module 字段名）", summary.contains("\"module\""));
        // 关键回归点：整个 sseEndpoint（连 base-uri 一起）都不该出现在日志里
        Assert.assertFalse("摘要泄露了 sseEndpoint", summary.contains("sseEndpoint"));
    }

    /** 摘要仍要保留排查问题需要的非敏感信息。 */
    @Test
    public void summarizeKeepsDiagnosticFields() {
        String summary = AiAgentAutoConfig.summarize(Map.of("spokenServer", buildTable()));

        Assert.assertTrue(summary.contains("key=spokenServer"));
        Assert.assertTrue(summary.contains("appName=spoken-server"));
        Assert.assertTrue(summary.contains("agentId=100003"));
        Assert.assertTrue(summary.contains("baseUrl=https://apis.example.cn"));
        Assert.assertTrue(summary.contains("model=gpt-4.1"));
        Assert.assertTrue(summary.contains("runnerAgent=spokenCoach"));
        // 密钥只留前 3 后 4，便于比对是哪一个 key，又不足以还原
        Assert.assertTrue("apiKey 应脱敏保留末 4 位", summary.contains("sk-****0000"));
        Assert.assertFalse("脱敏后不应还能看出中间内容", summary.contains("sk-FAKE"));
    }

    /** 脱敏规则：长串保留前 3 后 4，短串整体打码，null/空不抛异常。 */
    @Test
    public void maskBehaviour() {
        // 52 位：留前 3 位 + 末 4 位
        Assert.assertEquals("sk-****0000", AiAgentAutoConfig.mask(FAKE_LLM_KEY));
        // 14 位：仍走“前 3 后 4”分支
        Assert.assertEquals("123****1234", AiAgentAutoConfig.mask("12345678901234"));
        // 12 位及以下：整体打码
        Assert.assertEquals("****", AiAgentAutoConfig.mask("short"));
        Assert.assertEquals("****", AiAgentAutoConfig.mask("123456789012"));
        // 空值不抛异常
        Assert.assertEquals("(empty)", AiAgentAutoConfig.mask(null));
        Assert.assertEquals("(empty)", AiAgentAutoConfig.mask(""));
    }

    /** 缺字段的配置不能把摘要打成 NPE。 */
    @Test
    public void summarizeToleratesMissingSections() {
        AiAgentConfigTableVO bare = new AiAgentConfigTableVO();
        bare.setAppName("bare");

        String summary = AiAgentAutoConfig.summarize(Map.of("bare", bare));
        Assert.assertTrue(summary.contains("key=bare"));
        Assert.assertTrue(summary.contains("appName=bare"));

        Assert.assertEquals("", AiAgentAutoConfig.summarize(Map.of()));
    }
}

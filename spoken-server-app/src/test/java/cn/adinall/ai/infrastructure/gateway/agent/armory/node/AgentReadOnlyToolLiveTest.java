package cn.adinall.ai.infrastructure.gateway.agent.armory.node;

import cn.adinall.ai.infrastructure.config.agent.AiAgentConfigTableVO.Module.ChatModel.ToolMcp;
import cn.adinall.ai.infrastructure.config.agent.AiAgentConfigTableVO.Module.ChatModel.ToolMcp.LocalParameters;
import org.springframework.core.env.PropertySource;

import cn.adinall.ai.infrastructure.gateway.agent.model.ArmoryCommandEntity;
import cn.adinall.ai.infrastructure.config.agent.AiAgentConfigTableVO;
import cn.adinall.ai.infrastructure.config.agent.AiAgentAutoConfigProperties;
import cn.adinall.ai.infrastructure.gateway.agent.armory.factory.DefaultArmoryFactory;
import cn.adinall.ai.infrastructure.gateway.agent.armory.matter.mcp.client.factory.DefaultMcpClientFactory;
import cn.adinall.ai.infrastructure.gateway.agent.armory.matter.mcp.client.impl.LocalToolMcpCreateService;
import cn.adinall.ai.infrastructure.gateway.agent.chat.ChatService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/**
 * 功能：第零步的可重复在线验证，检查现有脚手架能否让当前模型调用本地只读工具。
 * <p>运行流程：</p>
 * <pre>生产 YAML 的测试副本 -> AiApiNode -> ChatModelNode -> AgentNode
 * -> AgentWorkflowNode -> RunnerNode -> IChatService -> 模型工具调用 -> 最终文本</pre>
 * <p>案例：模型查询练习目录，工具返回运行时随机凭据，回复包含该凭据，证明结果确实经过工具。</p>
 * <p>设计边界：不启动完整应用，不连接业务数据库，不调用数字人，不修改生产配置。
 * 使用真实装配节点和真实 ChatService，只在独立容器中挂载测试目录工具。
 * 默认跳过；显式设置 spoken.verify.live-tools=true 才访问配置中的在线模型。
 * 测试输出只保存模型名、次数、耗时和验证结果，不保存密钥或学员数据。</p>
 */
public class AgentReadOnlyToolLiveTest {

    /** 显式开启后验证同步、流式两种调用；普通回归不会因在线服务不可用失败。 */
    @Test(timeout = 180_000)
    public void currentModelCallsReadOnlyToolThroughExistingScaffold() throws Exception {
        assumeTrue("在线检查需显式启用", Boolean.getBoolean("spoken.verify.live-tools"));
        assertNotNull("需要进程环境变量 LK888_API_KEY", System.getenv("LK888_API_KEY"));

        StandardEnvironment environment = new StandardEnvironment();
        for (PropertySource<?> source : new YamlPropertySourceLoader().load("spoken-tool-check",
                new ClassPathResource("agent/spoken-server.yml"))) {
            environment.getPropertySources().addLast(source);
        }
        AiAgentAutoConfigProperties properties = Binder.get(environment).bind("ai.agent.config", AiAgentAutoConfigProperties.class).get();
        AiAgentConfigTableVO table = properties.getTables().values().stream()
                .filter(value -> "spoken-server".equals(value.getAppName())).findFirst().orElseThrow();
        // 测试只改内存副本：应用的 spokenCoach 和工具配置不受影响。
        table.getModule().getChatModel().setLogLlmPayload(false);
        LocalParameters local = new AiAgentConfigTableVO.Module.ChatModel.ToolMcp.LocalParameters();
        local.setName("verificationPracticeTools");
        ToolMcp tool = new AiAgentConfigTableVO.Module.ChatModel.ToolMcp();
        tool.setLocal(local);
        table.getModule().getChatModel().setToolMcpList(List.of(tool));
        table.getModule().getChatModel().setToolSkillsList(List.of());
        table.getModule().getAgents().get(0).setInstruction("You are a concise English practice assistant. "
                + "For a request to query available practice topics, call listPracticeTopics. "
                + "After the tool succeeds, reply in one plain sentence containing its verificationMarker and topic title. "
                + "Never invent tool results or read raw JSON aloud. {turn_policy?}");

        cn.adinall.ai.infrastructure.gateway.agent.armory.node.AgentReadOnlyToolLiveTest.ReadOnlyPracticeDirectory directory = new ReadOnlyPracticeDirectory();
        try (GenericApplicationContext container = new GenericApplicationContext()) {
            container.registerBean("verificationPracticeTools", org.springframework.ai.tool.ToolCallbackProvider.class,
                    () -> MethodToolCallbackProvider.builder().toolObjects(directory).build());
            container.refresh();
            assemble(table, container);
            DefaultArmoryFactory factory = new DefaultArmoryFactory();
            ReflectionTestUtils.setField(factory, "applicationContext", container);
            ChatService service = new ChatService();
            ReflectionTestUtils.setField(service, "defaultArmoryFactory", factory);

            long started = System.nanoTime();
            String owner = "stage-zero-tool-check";
            String agentId = table.getAgent().getAgentId();
            String sessionId = service.createSession(agentId, owner);
            try {
                String synchronous = String.join("", service.handleMessage(agentId, owner, sessionId,
                        "Query the available practice topics and report the verification marker."));
                assertTrue("同步回复也必须使用工具返回的凭据", synchronous.contains(directory.marker));
            } finally {
                service.closeSession(agentId, owner);
            }
            assertEquals("同步路径必须实际调用一次工具", 1, directory.calls.get());

            directory.rotate();
            String reply = service.handlePreparedTextStream(agentId, owner,
                    "Query the available practice topics and report the verification marker.", "")
                    .collectList().map(parts -> String.join("", parts)).block(Duration.ofSeconds(90));
            assertEquals("流式短会话必须实际调用一次工具", 1, directory.calls.get());
            assertNotNull(reply);
            assertTrue("最终文本必须使用工具返回的随机凭据", reply.contains(directory.marker));
            assertFalse("内部工具 JSON 不应进入播报文本", reply.contains("verificationMarker"));

            Path report = Path.of("target", "stage-zero-tool-live-report.json");
            Files.createDirectories(report.getParent());
            new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(report.toFile(), Map.of(
                    "model", table.getModule().getChatModel().getModel(),
                    "synchronousToolCalls", 1, "streamingToolCalls", directory.calls.get(),
                    "toolResultReachedFinalText", true,
                    "elapsedMs", Duration.ofNanos(System.nanoTime() - started).toMillis()));
        }
    }

    /**
     * 将真实节点组装进隔离容器；不绕过脚手架创建 OpenAiChatModel 或 LlmAgent。
     * 空 workflow 沿用生产配置的直接 Runner 分支，无须启动示例循环/并行节点。
     */
    void assemble(AiAgentConfigTableVO table, GenericApplicationContext container) throws Exception {
        LocalToolMcpCreateService local = new LocalToolMcpCreateService();
        ReflectionTestUtils.setField(local, "applicationContext", container);
        DefaultMcpClientFactory mcp = new DefaultMcpClientFactory();
        ReflectionTestUtils.setField(mcp, "localToolMcpCreateService", local);
        RunnerNode runner = new RunnerNode();
        ReflectionTestUtils.setField(runner, "applicationContext", container);
        AgentWorkflowNode workflow = new AgentWorkflowNode();
        ReflectionTestUtils.setField(workflow, "runnerNode", runner);
        AgentNode agent = new AgentNode();
        ReflectionTestUtils.setField(agent, "agentWorkflowNode", workflow);
        ChatModelNode model = new ChatModelNode();
        ReflectionTestUtils.setField(model, "defaultMcpClientFactory", mcp);
        ReflectionTestUtils.setField(model, "agentNode", agent);
        AiApiNode api = new AiApiNode();
        ReflectionTestUtils.setField(api, "chatModelNode", model);
        api.doApply(ArmoryCommandEntity.builder().aiAgentConfigTableVO(table).build(),
                new DefaultArmoryFactory.DynamicContext());
    }

    /**
     * 只读工具替身：目录不访问外部资源；计数与随机凭据仅用于检测真实执行。
     * 不提供写入工具，避免能力验证阶段影响用户配置。
     */
    public static class ReadOnlyPracticeDirectory {
        /** 本次路径调用次数；同步和流式分别验证，不能互相借用证据。 */
        private final AtomicInteger calls = new AtomicInteger();
        /** 每次验证随机生成，只能从工具结果获得，不能从提示词猜测。 */
        private String marker = UUID.randomUUID().toString();

        /** 当用户明确查询可用练习话题时调用；返回单个测试话题及验证凭据。 */
        @Tool(description = "Read the available English practice topics and their verification marker. Call when asked to query practice topics.")
        public Map<String, String> listPracticeTopics() {
            calls.incrementAndGet();
            return Map.of("topicTitle", "Food", "verificationMarker", marker);
        }

        /** 两条路径之间重置凭据，避免同步调用结果误充当流式成功证据。 */
        private void rotate() {
            calls.set(0);
            marker = UUID.randomUUID().toString();
        }
    }
}

package cn.adinall.ai.infrastructure.gateway.agent.armory.matter.mcp.client.impl;

import cn.adinall.ai.infrastructure.config.agent.AiAgentConfigTableVO;
import cn.adinall.ai.infrastructure.gateway.agent.armory.matter.mcp.client.TooMcpCreateService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 标准输入输出工具 MCP 构建服务（stdio 形态：把 MCP server 当子进程连）
 * <p>
 * 功能：把配置里的 command / args / env 组装成 ServerParameters，
 * 交给 StdioClientTransport 建同步客户端并 initialize()，最后返回该 server 的工具回调。
 * <pre>
 *   buildToolCallback(toolMcp)  ServerParameters.builder(command/args/env)
 *                               --> StdioClientTransport --> McpClient.sync --> initialize()
 *                               --> SyncMcpToolCallbackProvider --> ToolCallback[]
 * </pre>
 * 运行过程：
 * <pre>
 *   ChatModelNode --> DefaultMcpClientFactory（stdio 非空）--> buildToolCallback(toolMcp)
 *        |
 *        | (1) stdioConfig = toolMcp.getStdio()
 *        |     serverParameters = stdioConfig.getServerParameters()
 *        |
 *        | (2) ServerParameters.builder(serverParameters.getCommand())
 *        |        .args(serverParameters.getArgs())
 *        |        .env(serverParameters.getEnv())
 *        |        .build()
 *        |
 *        | (3) new StdioClientTransport(stdioParams,
 *        |          new JacksonMcpJsonMapper(new ObjectMapper()))
 *        |
 *        | (4) McpClient.sync(transport)
 *        |        .requestTimeout(Duration.ofSeconds(stdioConfig.getRequestTimeout()))
 *        |        .build()
 *        |     --> mcpSyncClient.initialize()   装配期就完成握手
 *        |
 *        | (5) SyncMcpToolCallbackProvider.builder().mcpClients(mcpSyncClient).build()
 *        v
 *   ToolCallback[] --> 回到 ChatModelNode 平铺进 toolCallbackList
 * </pre>
 * 案例：
 * <pre>
 *   输入: stdio = {name="filesystem", requestTimeout=10,
 *                  serverParameters={command="npx",
 *                                    args=["-y","@modelcontextprotocol/server-filesystem","/data"],
 *                                    env={}}}
 *   处理: ServerParameters.builder("npx").args([...]).env({}) --> StdioClientTransport
 *         --> requestTimeout=10 秒 --> initialize()
 *   输出: ToolCallback[]（该 MCP server 暴露的工具）
 *   失败例: stdio 下没写 server-parameters --> serverParameters 为 null，
 *          getCommand() 直接抛 NullPointerException
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>requestTimeout 的单位是秒（Duration.ofSeconds），
 *       与本工程 SSE 实现的毫秒（Duration.ofMillis）不同：同一个 request-timeout 键单位不一样。</li>
 *   <li>serverParameters 必须整体配置：getServerParameters() 的结果被直接解引用，
 *       漏配这一层会在装配期 NPE，而不是给出「命令缺失」这种可读错误。</li>
 *   <li>env 原样透传给 ServerParameters：密钥一类环境变量是随配置走的，
 *       因此这类值不应该提交进仓库。</li>
 *   <li>与 sse 形态一样在装配期 initialize()：等价于启动时就把 MCP 子进程连起来，
 *       启动环境里缺 npx / 缺依赖会直接导致应用起不来。</li>
 * </ul>
 */
@Slf4j
@Service
public class StdioToolMcpCreateService implements TooMcpCreateService {

    /** 将工具配置构建为模型可调用的工具回调。 */
    @Override
    public ToolCallback[] buildToolCallback(AiAgentConfigTableVO.Module.ChatModel.ToolMcp toolMcp) {
        AiAgentConfigTableVO.Module.ChatModel.ToolMcp.StdioServerParameters stdioConfig = toolMcp.getStdio();

        AiAgentConfigTableVO.Module.ChatModel.ToolMcp.StdioServerParameters.ServerParameters serverParameters = stdioConfig.getServerParameters();

        ServerParameters stdioParams = ServerParameters.builder(serverParameters.getCommand())
                .args(serverParameters.getArgs())
                .env(serverParameters.getEnv())
                .build();

        McpSyncClient mcpSyncClient = McpClient.sync(new StdioClientTransport(stdioParams, new JacksonMcpJsonMapper(new ObjectMapper())))
                .requestTimeout(Duration.ofSeconds(stdioConfig.getRequestTimeout())).build();

        McpSchema.InitializeResult initialize = mcpSyncClient.initialize();

        return SyncMcpToolCallbackProvider.builder().mcpClients(mcpSyncClient).build()
                .getToolCallbacks();
    }

}

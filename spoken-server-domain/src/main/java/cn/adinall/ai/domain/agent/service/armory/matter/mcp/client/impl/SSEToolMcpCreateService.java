package cn.adinall.ai.domain.agent.service.armory.matter.mcp.client.impl;

import cn.adinall.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import cn.adinall.ai.domain.agent.service.armory.matter.mcp.client.TooMcpCreateService;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;

import java.net.URL;
import java.time.Duration;

/**
 * SSE 工具 MCP 构建服务（sse 形态：用 HttpClientSseClientTransport 连远端 MCP server）
 * <p>
 * 功能：把配置里的 baseUri / sseEndpoint 拆解成「基地址 + SSE 端点」，
 * 建一条 SSE 传输、同步客户端并 initialize()，最后返回该 server 的工具回调。
 * <pre>
 *   buildToolCallback(toolMcp)  解析 base-uri/sse-endpoint --> McpClient.sync --> initialize()
 *                               --> SyncMcpToolCallbackProvider --> ToolCallback[]
 * </pre>
 * 运行过程：
 * <pre>
 *   ChatModelNode --> DefaultMcpClientFactory（sse 非空）--> buildToolCallback(toolMcp)
 *        |
 *        | (1) sseConfig = toolMcp.getSse()
 *        |     originalBaseUri = sseConfig.getBaseUri()
 *        |     sseEndpoint = sseConfig.getSseEndpoint()
 *        |
 *        | (2) sseEndpoint 为空时，从 base-uri 里拆出来：
 *        |        new URL(originalBaseUri) --> protocol / host / port
 *        |        baseUrl = port == -1 ? protocol://host : protocol://host:port
 *        |        index = originalBaseUri.indexOf(baseUrl)
 *        |        sseEndpoint = originalBaseUri.substring(index + baseUrl.length())
 *        |        baseUri = baseUrl
 *        |
 *        | (3) sseEndpoint 仍为空 --> 兜底 "/sse"
 *        |
 *        | (4) HttpClientSseClientTransport.builder(baseUri).sseEndpoint(sseEndpoint).build()
 *        |
 *        | (5) McpClient.sync(transport)
 *        |        .requestTimeout(Duration.ofMillis(sseConfig.getRequestTimeout()))
 *        |        .build()
 *        |     --> mcpSyncClient.initialize()   装配期就与远端握手
 *        |
 *        | (6) SyncMcpToolCallbackProvider.builder().mcpClients(mcpSyncClient).build()
 *        v
 *   ToolCallback[] --> 回到 ChatModelNode 平铺进 toolCallbackList
 * </pre>
 * 案例：
 * <pre>
 *   输入: sse = {name="baidu-search",
 *               baseUri="http://appbuilder.baidu.com/v2/ai_search/mcp/",
 *               sseEndpoint=null, requestTimeout=3000}
 *   处理: sseEndpoint 为空 --> URL 解析出 protocol="http"、host="appbuilder.baidu.com"、port=-1
 *         --> baseUrl="http://appbuilder.baidu.com"
 *         --> index=0 --> sseEndpoint="/v2/ai_search/mcp/"、baseUri="http://appbuilder.baidu.com"
 *         --> 建 SSE 传输 --> requestTimeout=3000 毫秒 --> initialize()
 *   输出: ToolCallback[]（该远端 MCP server 暴露的工具）；日志 "tool sse mcp initialize ..."
 *   另一例: sseEndpoint 已配置（如 "sse?api_key=..."）时跳过拆解，baseUri 原样使用
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>requestTimeout 的单位是毫秒（Duration.ofMillis），
 *       而 StdioToolMcpCreateService 用的是秒（Duration.ofSeconds）：
 *       同一个 request-timeout 配置键在两种形态下差 1000 倍，写配置时必须看形态。</li>
 *   <li>未配 sse-endpoint 时会把 base-uri 里 host 之后的部分当 endpoint，并把 baseUri 截成
 *       protocol://host[:port]：因此 base-uri 既要能被 URL 解析，也要保留路径部分。</li>
 *   <li>装配期就 initialize()：地址写错、鉴权失败的远端 MCP 会在启动期失败，
 *       不会等到第一次对话才暴露，代价是启动变慢且依赖远端可达。</li>
 *   <li>mcpSyncClient 是局部变量：本方法既不保存也不关闭它，
 *       返回的 ToolCallback[] 是从这个客户端上取下来的，生命周期跟着返回值走。</li>
 * </ul>
 */
@Slf4j
@Service
public class SSEToolMcpCreateService implements TooMcpCreateService {

    @Override
    public ToolCallback[] buildToolCallback(AiAgentConfigTableVO.Module.ChatModel.ToolMcp toolMcp) throws Exception {
        AiAgentConfigTableVO.Module.ChatModel.ToolMcp.SSEServerParameters sseConfig = toolMcp.getSse();

        // 真实地址示例（api_key 走环境变量，不要写进仓库）：
        // http://appbuilder.baidu.com/v2/ai_search/mcp/sse?api_key=bce-v3/ALTAK-****

        String originalBaseUri = sseConfig.getBaseUri();
        String baseUri = originalBaseUri;
        String sseEndpoint = sseConfig.getSseEndpoint();

        if (StringUtils.isBlank(sseEndpoint)) {
            URL url = new URL(originalBaseUri);

            String protocol = url.getProtocol();
            String host = url.getHost();
            int port = url.getPort();

            String baseUrl = port == -1 ? protocol + "://" + host : protocol + "://" + host + ":" + port;

            int index = originalBaseUri.indexOf(baseUrl);
            if (index != -1) {
                sseEndpoint = originalBaseUri.substring(index + baseUrl.length());
            }

            baseUri = baseUrl;
        }

        sseEndpoint = StringUtils.isBlank(sseEndpoint) ? "/sse" : sseEndpoint;

        HttpClientSseClientTransport sseClientTransport = HttpClientSseClientTransport
                .builder(baseUri)
                .sseEndpoint(sseEndpoint)
                .build();

        McpSyncClient mcpSyncClient = McpClient
                .sync(sseClientTransport)
                .requestTimeout(Duration.ofMillis(sseConfig.getRequestTimeout())).build();
        McpSchema.InitializeResult initialize = mcpSyncClient.initialize();

        log.info("tool sse mcp initialize {}", initialize);

        return SyncMcpToolCallbackProvider.builder()
                .mcpClients(mcpSyncClient).build()
                .getToolCallbacks();
    }

}
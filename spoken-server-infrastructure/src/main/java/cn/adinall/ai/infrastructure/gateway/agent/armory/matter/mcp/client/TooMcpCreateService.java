package cn.adinall.ai.infrastructure.gateway.agent.armory.matter.mcp.client;

import cn.adinall.ai.infrastructure.config.agent.AiAgentConfigTableVO;
import org.springframework.ai.tool.ToolCallback;

/**
 * 工具 MCP 构建服务接口（三种接入形态的统一出口）
 * <p>
 * 功能：只定义一件事 —— 把配置里的一段 toolMcp 变成 Spring AI 能挂到模型上的 ToolCallback 数组。
 * 三种形态各有一个实现，由 DefaultMcpClientFactory 按「配置里哪个参数非空」选中。
 * <pre>
 *   buildToolCallback(ToolMcp)  按配置连上 MCP 工具源，返回 ToolCallback[]
 * </pre>
 * 体系架构（谁实现、谁选实现、谁消费）：
 * <pre>
 *   AiAgentConfigTableVO.Module.ChatModel.toolMcpList
 *        |
 *        v
 *   ChatModelNode.doApply(): for toolMcp in toolMcpList
 *        |  defaultMcpClientFactory.getTooMcpCreateService(toolMcp)
 *        v
 *   TooMcpCreateService  <-- 本接口
 *        |
 *        +--> LocalToolMcpCreateService   local 非空：从容器取 ToolCallbackProvider
 *        +--> SSEToolMcpCreateService     sse   非空：SSE 传输连 MCP server
 *        +--> StdioToolMcpCreateService   stdio 非空：子进程传输连 MCP server
 *        |
 *        v
 *   ToolCallback[] --> OpenAiChatOptions.toolCallbacks(...)  绑到 ChatModel 默认参数
 * </pre>
 * 案例：
 * <pre>
 *   输入: toolMcp = {local={name="myToolCallbackProvider"}}
 *   处理: DefaultMcpClientFactory 命中 local 分支返回 LocalToolMcpCreateService，
 *         buildToolCallback(toolMcp) 内部 applicationContext.getBean("myToolCallbackProvider")
 *   输出: ToolCallback[]（本工程里是 MyTestMcpService.toUpperCase 对应的回调）
 *   另一例: toolMcp = {sse={name="baidu-search",
 *                    baseUri="http://appbuilder.baidu.com/v2/ai_search/mcp/",
 *                    sseEndpoint="sse?api_key=...", requestTimeout=5000}}
 *         走 SSEToolMcpCreateService，装配期完成 initialize() 后返回远端工具回调
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>把「连 MCP」抽象成接口，是为了让 ChatModelNode 只依赖这一个方法：
 *       新增一种接入形态（如 streamable http）只需加实现并在工厂加一个分支，节点不用改。</li>
 *   <li>方法签名带 throws Exception：连远端、拉子进程都可能失败，
 *       异常直接冒到装配期（启动期），而不是留到第一次对话才炸。</li>
 *   <li>返回值是数组而不是 List：调用方 ChatModelNode 用 List.of(...) 平铺进统一的
 *       toolCallbackList，与 Skills 那条来源一视同仁。</li>
 * </ul>
 *
 * @author 魏玉石
 * 2026/1/2 09:31
 */
public interface TooMcpCreateService {

    /** 将工具配置构建为模型可调用的工具回调。 */
    ToolCallback[] buildToolCallback(AiAgentConfigTableVO.Module.ChatModel.ToolMcp toolMcp) throws Exception;

}

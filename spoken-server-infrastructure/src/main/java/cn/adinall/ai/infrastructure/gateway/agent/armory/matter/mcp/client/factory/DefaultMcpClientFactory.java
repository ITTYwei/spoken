package cn.adinall.ai.infrastructure.gateway.agent.armory.matter.mcp.client.factory;

import cn.adinall.ai.infrastructure.config.agent.AiAgentConfigTableVO;
import cn.adinall.ai.infrastructure.gateway.agent.armory.matter.mcp.client.TooMcpCreateService;
import cn.adinall.ai.infrastructure.gateway.agent.armory.matter.mcp.client.impl.LocalToolMcpCreateService;
import cn.adinall.ai.infrastructure.gateway.agent.armory.matter.mcp.client.impl.SSEToolMcpCreateService;
import cn.adinall.ai.infrastructure.gateway.agent.armory.matter.mcp.client.impl.StdioToolMcpCreateService;
import cn.adinall.ai.types.enums.ResponseCode;
import cn.adinall.ai.types.exception.AppException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

/**
 * MCP 客户端工厂（按配置形态选择 TooMcpCreateService 实现）
 * <p>
 * 功能：看 toolMcp 的哪个参数非空，返回对应的构建服务；三个都为空就直接按配置错误报错。
 * <pre>
 *   getTooMcpCreateService(toolMcp)  local 非空 -> Local；sse 非空 -> SSE；
 *                                    stdio 非空 -> Stdio；都不匹配 -> 抛 AppException
 * </pre>
 * 运行过程：
 * <pre>
 *   ChatModelNode.doApply(): for toolMcp in module.chatModel.toolMcpList
 *        |
 *        v
 *   getTooMcpCreateService(toolMcp)
 *        |
 *        +--> toolMcp.getLocal() != null --> localToolMcpCreateService
 *        +--> toolMcp.getSse()   != null --> sseToolMcpCreateService
 *        +--> toolMcp.getStdio() != null --> stdioToolMcpCreateService
 *        +--> 都不满足                   --> throw AppException(NOT_FOUND_METHOD)
 *        v
 *   ChatModelNode 继续调 buildToolCallback(toolMcp) 拿 ToolCallback[]
 * </pre>
 * 案例：
 * <pre>
 *   输入: toolMcp = {sse={name="baidu-map", baseUri="https://mcp.map.baidu.com/",
 *                    sseEndpoint="sse?ak=...", requestTimeout=5000}}
 *   处理: getLocal() 与 getStdio() 都是 null，getSse() 非空 --> SSEToolMcpCreateService
 *   输出: SSEToolMcpCreateService 实例
 *   失败例: toolMcp = {}（三个参数都没配）
 *          --> AppException(code=NOT_FOUND_METHOD)，装配在启动期失败
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>用「哪个参数非空」判断形态，而不是配置里再补一个 type 字段：
 *       配置结构本身就是判据，yml 少写一层，也少一处可能写错的地方。</li>
 *   <li>判断有先后：local 先于 sse 先于 stdio。一条配置里如果同时写了多个参数，
 *       只有第一个生效，其余被静默忽略。</li>
 *   <li>三者都不匹配时抛 NOT_FOUND_METHOD：tool-mcp-list 里把 key 写错（如写成 ssee）
 *       会在启动期暴露，不会悄悄装配出一个「没工具却看起来正常」的模型。</li>
 *   <li>返回的三个实现都是 @Service 单例（@Resource 注入），工厂本身无状态，可放心复用。</li>
 * </ul>
 */
@Slf4j
@Service
public class DefaultMcpClientFactory {

    @Resource
    private LocalToolMcpCreateService localToolMcpCreateService;

    @Resource
    private SSEToolMcpCreateService sseToolMcpCreateService;

    @Resource
    private StdioToolMcpCreateService stdioToolMcpCreateService;

    /** 读取与传输类型匹配的 MCP 工具构建服务。 */
    public TooMcpCreateService getTooMcpCreateService(AiAgentConfigTableVO.Module.ChatModel.ToolMcp toolMcp) {
        if (null != toolMcp.getLocal()) return localToolMcpCreateService;
        if (null != toolMcp.getSse()) return sseToolMcpCreateService;
        if (null != toolMcp.getStdio()) return stdioToolMcpCreateService;
        throw new AppException(ResponseCode.NOT_FOUND_METHOD.getCode(), ResponseCode.NOT_FOUND_METHOD.getInfo());
    }

}

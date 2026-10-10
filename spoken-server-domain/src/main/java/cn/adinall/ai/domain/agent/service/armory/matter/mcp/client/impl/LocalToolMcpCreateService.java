package cn.adinall.ai.domain.agent.service.armory.matter.mcp.client.impl;

import cn.adinall.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import cn.adinall.ai.domain.agent.service.armory.matter.mcp.client.TooMcpCreateService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

/**
 * 本地工具 MCP 构建服务（local 形态：不连远端，直接用本进程容器里已有的 ToolCallbackProvider）
 * <p>
 * 功能：按配置里的 local.name 从 Spring 容器取出一个 ToolCallbackProvider，摊平成工具回调数组。
 * <pre>
 *   buildToolCallback(toolMcp)  getBean(local.name) 强转 ToolCallbackProvider --> getToolCallbacks()
 * </pre>
 * 运行过程（本工程的实际链路，配置与代码一一对应）：
 * <pre>
 *   demo.yml: module.chat-model.tool-mcp-list[].local.name = "myToolCallbackProvider"
 *        |
 *        v
 *   DefaultMcpClientFactory: local 非空 --> LocalToolMcpCreateService（本类）
 *        |
 *        v
 *   buildToolCallback(toolMcp)
 *        |
 *        | (1) local = toolMcp.getLocal(); name = local.getName()
 *        |
 *        | (2) applicationContext.getBean(name)
 *        |        |
 *        |        v
 *        |     Application.testTools(MyTestMcpService) 里注册的 @Bean("myToolCallbackProvider")
 *        |        = MethodToolCallbackProvider.builder().toolObjects(testService).build()
 *        |
 *        | (3) (ToolCallbackProvider) 强转 --> getToolCallbacks()
 *        v
 *   ToolCallback[]（本工程里对应 MyTestMcpService 上 @Tool 标注的方法）
 * </pre>
 * 案例：
 * <pre>
 *   输入: toolMcp = {local={name="myToolCallbackProvider"}}
 *   处理: applicationContext.getBean("myToolCallbackProvider") 得到 MethodToolCallbackProvider，
 *         强转后调 getToolCallbacks()
 *   输出: ToolCallback[]，本工程里含 toUpperCase 一个工具；
 *         日志 "tool local mcp initialize myToolCallbackProvider"
 *   失败例: name 写成 "myToolCallbackProviderX" --> NoSuchBeanDefinitionException（装配期失败）
 *          name 指向的 bean 不是 ToolCallbackProvider --> ClassCastException
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>与 sse / stdio 的本质差别：不 new 任何 MCP 客户端、不发起网络或子进程，
 *       工具早已在本进程里以 Spring bean 形式存在，这里只做「按名字取 + 摊平回调」。</li>
 *   <li>local.name 是配置与容器 bean 名之间的硬契约：@Bean("myToolCallbackProvider")
 *       改了名字就一定要改 yml，反之亦然。</li>
 *   <li>方法没有声明 throws：getBean 和强转都是本地操作，取不到或类型不对时
 *       按 Spring 的默认语义直接抛，不需要额外包装。</li>
 *   <li>日志打在取到 provider 之后：日志出现即代表 bean 名已解析成功，
 *       排查「配置名写错」时先看这条日志有没有打印。</li>
 * </ul>
 */
@Slf4j
@Service
public class LocalToolMcpCreateService  implements TooMcpCreateService {

    @Resource
    protected ApplicationContext applicationContext;

    @Override
    public ToolCallback[] buildToolCallback(AiAgentConfigTableVO.Module.ChatModel.ToolMcp toolMcp) {
        AiAgentConfigTableVO.Module.ChatModel.ToolMcp.LocalParameters local = toolMcp.getLocal();
        String name = local.getName();

        ToolCallbackProvider localToolCallbackProvider = (ToolCallbackProvider) applicationContext.getBean(local.getName());
        log.info("tool local mcp initialize {}", name);

        return localToolCallbackProvider.getToolCallbacks();
    }

}
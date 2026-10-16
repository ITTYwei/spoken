package cn.adinall.ai;

import cn.adinall.ai.infrastructure.gateway.agent.armory.matter.mcp.server.MyTestMcpService;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.annotation.Configurable;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * 应用启动类（spoken-server-app 的唯一入口，多模块的装配收口点）
 * <p>
 * 功能：启动 Spring Boot 容器，并额外注册一个「把本地服务的方法暴露成 AI 工具」的 Bean。
 * <pre>
 *   main(args)              启动 Spring Boot（内部调 SpringApplication.run(Application.class)）
 *   testTools(testService)  把 MyTestMcpService 上的 @Tool 方法包成 ToolCallbackProvider
 *                           注册到容器，bean 名固定为 myToolCallbackProvider
 * </pre>
 * 体系架构（启动 -> 装配 -> 运行）：
 * <pre>
 *   main()
 *        | SpringApplication.run(Application.class)
 *        | 从 cn.adinall.ai 开始扫描：各模块的 @Service / @Configuration / @RestController 都在这个根包下
 *        v
 *   容器刷新完成（IArmoryService、AiAgentAutoConfigProperties、ToolCallbackProvider 等 Bean 就绪）
 *        |
 *        | 发布 ApplicationReadyEvent
 *        v
 *   AiAgentAutoConfig.onApplicationEvent(event)
 *        | ai.agent.config.enabled=true 且 tables 非空
 *        v
 *   IArmoryService.acceptArmoryAgents(tables.values())
 *        | 装配产物按 agentId 注册进容器
 *        v
 *   运行期：ChatService 按 agentId 取 AiAgentRegisterVO 发起对话
 * </pre>
 * 案例：
 * <pre>
 *   输入: java -jar spoken-server-app.jar（不带命令行参数）
 *   处理: 启动容器；若 demo.yml 里 tool-mcp-list[].local.name="myToolCallbackProvider"，
 *         装配链会 applicationContext.getBean("myToolCallbackProvider") 取到本类注册的 Bean
 *   输出: 服务监听配置端口；GET /api/v1/query_ai_agent_config_list 返回
 *         {"code":"0000","info":"成功","data":[...]}
 *         若 ai.agent.config.enabled=false，启动日志出现
 *         "Ai Agent 自动装配未启用（ai.agent.config.enabled=false），跳过"
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>启动类只放在 app 模块、其余模块只提供 Bean：@SpringBootApplication 兼做组件扫描，
 *       根包 cn.adinall.ai 覆盖全部模块，所以新增模块不用改本类。</li>
 *   <li>main 里刻意没有其它代码：启动期动作全部交给事件监听器（见 AiAgentAutoConfig），
 *       这样「什么时候装配什么」是显式声明的，而不是藏在 main 的调用顺序里。</li>
 *   <li>@Bean("myToolCallbackProvider") 的 Bean 名是硬契约：必须与 yml 里
 *       module.chat-model.tool-mcp-list[].local.name 一致，否则装配期 getBean 会抛
 *       NoSuchBeanDefinitionException；改 Bean 名等于改配置契约。</li>
 *   <li>run(Application.class) 不传 args：命令行参数不会参与配置覆盖，
 *       想用 --server.port=8081 这种方式临时改配置必须把 args 传下去。</li>
 *   <li>@Configurable（AspectJ 织入，让非 Spring 创建的对象也能被注入）是历史遗留：
 *       新代码请用构造器注入，不要依赖它。</li>
 * </ul>
 */
@SpringBootApplication
@Configurable
public class Application {

    /** 启动应用入口。 */
    public static void main(String[] args) {
        SpringApplication.run(Application.class);
    }

    /** 注册示例工具实现供工具回调使用。 */
    @Bean("myToolCallbackProvider")
    public ToolCallbackProvider testTools(MyTestMcpService testService) {
        return MethodToolCallbackProvider.builder().toolObjects(testService).build();
    }

}

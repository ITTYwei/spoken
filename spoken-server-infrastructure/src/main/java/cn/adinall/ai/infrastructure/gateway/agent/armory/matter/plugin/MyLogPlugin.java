package cn.adinall.ai.infrastructure.gateway.agent.armory.matter.plugin;

import com.google.adk.plugins.LoggingPlugin;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 日志插件（ADK 插件体系里最简的一种形态：只注册一个具名 bean）
 * <p>
 * 功能：本类不写一行代码，靠继承 LoggingPlugin 获得日志能力，
 * 再以 bean 名 "myLogPlugin" 注册进容器，供 yml 的 runner.plugin-name-list 按名字引用。
 * <pre>
 *   （无自有方法）  全部行为继承自 com.google.adk.plugins.LoggingPlugin
 * </pre>
 * 运行过程（从配置到挂上 Runner）：
 * <pre>
 *   only-one-agent.yml: module.runner.plugin-name-list = [myTestPlugin, myLogPlugin]
 *        |
 *        v
 *   RunnerNode.getRunner()
 *        |  for pluginName in pluginNameList
 *        |     getBean(pluginName)  --> BasePlugin
 *        v
 *   new InMemoryRunner(baseAgent, appName, plugins)
 *        |
 *        v
 *   对话时由 ADK 回调插件，本 bean 打印日志
 * </pre>
 * 案例：
 * <pre>
 *   输入: module.runner = {agentName="main-agent", pluginNameList=["myLogPlugin"]}
 *   处理: RunnerNode 里 getBean("myLogPlugin") 取到本类实例，放进 plugins 列表
 *   输出: 无返回值；容器里那个 AiAgentRegisterVO 的 InMemoryRunner 带上 myLogPlugin
 *   失败例: plugin-name-list 写成 "myLogplugin"（大小写不符）--> NoSuchBeanDefinitionException，装配期失败
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>用「空子类 + 具名 bean」表达「要开日志插件」：配置里只写名字，
 *       行为由父类决定，调整日志行为不用碰 yml。</li>
 *   <li>bean 名 "myLogPlugin" 是配置与容器之间唯一的契约（RunnerNode 按名字取），
 *       改名必须同步改 yml 的 plugin-name-list。</li>
 *   <li>本类必须是 BasePlugin 的子类：RunnerNode 把 getBean 的结果按 BasePlugin 使用，
 *       换成不相关的类型会在那里失败。</li>
 *   <li>插件是 Spring 单例，同一个 Runner 的多次对话共用同一个实例，
 *       因此不要往插件里放与会话相关的状态。</li>
 * </ul>
 */
@Slf4j
@Service("myLogPlugin")
public class MyLogPlugin extends LoggingPlugin {
}

package cn.adinall.ai.domain.agent.service.armory.matter.plugin;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.CallbackContext;
import com.google.adk.agents.InvocationContext;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.plugins.BasePlugin;
import com.google.genai.types.Content;
import io.reactivex.rxjava3.core.Maybe;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 测试用插件（ADK 插件回调的最小示例：先打日志，再把控制权交回父类）
 * <p>
 * 功能：在「用户消息进入」和「智能体开始执行」两个时机各打一条日志，其他行为保持父类默认。
 * <pre>
 *   onUserMessageCallback(InvocationContext, Content)  打印用户输入文本
 *   beforeAgentCallback(BaseAgent, CallbackContext)    打印即将执行的智能体名
 * </pre>
 * 运行过程（一个插件是怎么被挂上并回调的）：
 * <pre>
 *   only-one-agent.yml: module.runner.plugin-name-list = [myTestPlugin, myLogPlugin]
 *        |
 *        v
 *   RunnerNode.getRunner(): getBean("myTestPlugin") --> BasePlugin
 *        |
 *        v
 *   new InMemoryRunner(baseAgent, appName, plugins)
 *        |
 *        | 对话开始
 *        v
 *   onUserMessageCallback(invocationContext, userMessage)
 *        |  log.info("用户输入信息:{}", userMessage.text())
 *        v
 *   super.onUserMessageCallback(...)   返回父类结果（本类不改变返回值）
 *        |
 *        v
 *   beforeAgentCallback(agent, callbackContext)
 *        |  log.info("智能体名称:{}", agent.name())
 *        v
 *   super.beforeAgentCallback(...)     继续正常执行
 * </pre>
 * 案例：
 * <pre>
 *   输入: 用户消息文本 "hello"，即将执行的智能体 name="main-agent"
 *   处理: 两个回调各打一条日志后都 return super.xxx(...)
 *   输出: 日志 "用户输入信息:hello" 和 "智能体名称:main-agent"；
 *         因为返回值来自父类，对话结果与不挂本插件时一致
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>回调里「先记日志、再 return super(...)」：既不吞掉也不伪造返回值，
 *       所以挂上本插件对对话结果无副作用，排查问题时可先摘掉它排除干扰。</li>
 *   <li>Spring 创建 bean 走无参构造，因此 super("MyTestPlugin") 生效；
 *       带参构造 MyTestPlugin(String name) 是留给手工 new 的场景。</li>
 *   <li>bean 名 "myTestPlugin" 由 @Service 指定，RunnerNode 按 yml 的 plugin-name-list 取用它，
 *       两者必须一致。</li>
 *   <li>回调返回值类型是 Maybe（ADK 的异步风格）：本类不自行构造 Maybe，
 *       只透传父类结果，避免在插件里引入对异步语义的额外理解。</li>
 * </ul>
 */
@Slf4j
@Service("myTestPlugin")
public class MyTestPlugin extends BasePlugin {

    public MyTestPlugin(String name) {
        super(name);
    }

    public MyTestPlugin() {
        super("MyTestPlugin");
    }

    @Override
    public Maybe<Content> onUserMessageCallback(InvocationContext invocationContext, Content userMessage) {
        log.info("用户输入信息:{}", userMessage.text());
        return super.onUserMessageCallback(invocationContext, userMessage);
    }

    @Override
    public Maybe<Content> beforeAgentCallback(BaseAgent agent, CallbackContext callbackContext) {
        String agentName = agent.name();
        log.info("智能体名称:{}", agentName);
        return super.beforeAgentCallback(agent, callbackContext);
    }

}

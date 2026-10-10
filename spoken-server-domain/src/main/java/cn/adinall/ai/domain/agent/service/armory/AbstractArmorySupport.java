package cn.adinall.ai.domain.agent.service.armory;

import cn.adinall.ai.domain.agent.model.entity.ArmoryCommandEntity;
import cn.adinall.ai.domain.agent.model.valobj.AiAgentRegisterVO;
import cn.adinall.ai.domain.agent.service.armory.factory.DefaultArmoryFactory;
import cn.bugstack.wrench.design.framework.tree.AbstractMultiThreadStrategyRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.ApplicationContext;

import javax.annotation.Resource;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/**
 * 装配节点基类（责任链上所有节点的公共父类）
 * <p>
 * 功能：把「每个装配节点都要用的容器操作」收在这里，节点只关心自己的装配逻辑。
 * <pre>
 *   registerBean(name, class, instance)  动态注册单例 bean（同名的先摘掉再注册）
 *   getBean(name)                        按名字从容器取 bean（插件/MCP 等）
 *   multiThread(...)                     预留的多线程钩子，当前为空实现
 * </pre>
 * 体系架构（继承关系）：
 * <pre>
 *   AbstractMultiThreadStrategyRouter   （xfg-wrench 设计框架：doApply -> router -> get）
 *        |
 *        v
 *   AbstractArmorySupport               （本类：容器工具 + 日志 + applicationContext）
 *        |
 *        +--> RootNode          --> AiApiNode
 *        +--> AiApiNode         --> ChatModelNode
 *        +--> ChatModelNode     --> AgentNode
 *        +--> AgentNode         --> AgentWorkflowNode
 *        +--> AgentWorkflowNode --> Loop/Parallel/Sequential/AgentNode
 *        +--> RunnerNode         （链尾，无后继）
 * </pre>
 * 运行过程（框架回调本类工具方法的时机）：
 * <pre>
 *   AgentWorkflowNode / RunnerNode 的 doApply()
 *        |
 *        | (1) getBean("myLogPlugin")
 *        |        |
 *        |        v
 *        |     applicationContext.getBean(name) --> BasePlugin
 *        |
 *        | (2) registerBean(agentId, AiAgentRegisterVO.class, vo)
 *        |        |
 *        |        +--> containsBeanDefinition(agentId) ? removeBeanDefinition : 跳过
 *        |        +--> genericBeanDefinition(beanClass, () -> beanInstance)
 *        |        +--> setScope(SINGLETON) --> registerBeanDefinition
 *        v
 *   容器里 agentId 指向最新装配结果，相同 agentId 重复装配也不会抛错
 * </pre>
 * 案例：
 * <pre>
 *   输入: registerBean("3001", AiAgentRegisterVO.class, vo{appName="demo-app"})
 *   处理: 摘掉旧的 "3001" 定义 -> 注册新的 singleton 定义（实例由 lambda 直接给出，不经过构造器推断）
 *   输出: 无返回值，日志 "成功注册Bean: 3001"；此后 getBean("3001") 返回同一个 vo 实例
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>registerBean 加 synchronized：BeanFactory 的定义表不是为并发写设计的，
 *       智能体批量装配（含并行节点）必须串行化写入，否则可能出现定义/实例不一致。</li>
 *   <li>同名 bean 先移除再注册：装配支持重复执行（热更新配置后重新装配），
 *       不先摘掉会抛 BeanDefinitionOverrideException。</li>
 *   <li>beanName 用 agentId：运行期 ChatService 就是按 agentId 取回装配产物的，
 *       命名规则是装配期与运行期之间唯一的契约。</li>
 *   <li>multiThread 留空是刻意的：目前所有装配步骤都必须串行（后面节点依赖前面节点写入的上下文）。</li>
 * </ul>
 */
public abstract class AbstractArmorySupport extends AbstractMultiThreadStrategyRouter<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, AiAgentRegisterVO> {

    protected final Logger log = LoggerFactory.getLogger(AbstractArmorySupport.class);

    @Resource
    protected ApplicationContext applicationContext;

    @Override
    protected void multiThread(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws ExecutionException, InterruptedException, TimeoutException {

    }

    /**
     * 通用的Bean注册方法
     *
     * @param beanName  Bean名称
     * @param beanClass Bean类型
     * @param &lt;T&gt;       Bean类型
     */
    protected synchronized <T> void registerBean(String beanName, Class<T> beanClass, T beanInstance) {
        DefaultListableBeanFactory beanFactory = (DefaultListableBeanFactory) applicationContext.getAutowireCapableBeanFactory();

        // 注册Bean
        BeanDefinitionBuilder beanDefinitionBuilder = BeanDefinitionBuilder.genericBeanDefinition(beanClass, () -> beanInstance);
        BeanDefinition beanDefinition = beanDefinitionBuilder.getRawBeanDefinition();
        beanDefinition.setScope(BeanDefinition.SCOPE_SINGLETON);

        // 如果Bean已存在，先移除
        if (beanFactory.containsBeanDefinition(beanName)) {
            beanFactory.removeBeanDefinition(beanName);
        }

        // 注册新的Bean
        beanFactory.registerBeanDefinition(beanName, beanDefinition);

        log.info("成功注册Bean: {}", beanName);
    }

    protected <T> T getBean(String beanName) {
        return (T) applicationContext.getBean(beanName);
    }

}

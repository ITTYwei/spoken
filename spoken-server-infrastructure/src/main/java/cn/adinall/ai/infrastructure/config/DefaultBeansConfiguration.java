package cn.adinall.ai.infrastructure.config;

import cn.adinall.ai.domain.scenario.adapter.repository.IScenarioRepository;
import cn.adinall.ai.infrastructure.adapter.repository.InMemoryScenarioRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 与具体数字人后端无关的默认 Bean 装配（当前只有场景目录这一个）
 * <p>
 * 功能：为领域端口提供「不接任何外部系统也能跑」的兜底实现，接真实实现时可直接覆盖。
 * <pre>
 *   defaultScenarioRepository()   产出 IScenarioRepository（@ConditionalOnMissingBean）
 * </pre>
 * 运行过程（容器里最终装配的是哪个场景实现）：
 * <pre>
 *   Spring 启动扫描到本类
 *        |
 *        v
 *   defaultScenarioRepository()
 *        |
 *        +--> 容器里已有 IScenarioRepository（例如将来接真实场景服务）
 *        |        --> 本方法不执行，沿用已有的
 *        |
 *        +--> 没有 --> new InMemoryScenarioRepository()
 *        v
 *   CatalogCaseImpl / ConversationCaseImpl 注入到 IScenarioRepository
 *        |
 *        +--> CatalogCaseImpl.listScenarios()   -> scenarioRepository.listAll()
 *        +--> ConversationCaseImpl 建会话/取开场白 -> scenarioRepository.findById(scenarioId)
 * </pre>
 * 案例：
 * <pre>
 *   输入: 没有任何自定义 IScenarioRepository Bean（当前仓库的默认状态）
 *   处理: Spring 调用 defaultScenarioRepository()
 *   输出: 容器里 IScenarioRepository 指向 InMemoryScenarioRepository；
 *         catalog 用例 listScenarios() 返回两个场景：
 *         free（自由对话，音色 en-US-AriaNeural）
 *         与 interview（英文面试，音色 en-US-JennyNeural）
 *   覆盖: 若另一个 @Configuration 提供了 IScenarioRepository Bean，本方法跳过，
 *         两个实现不会同时生效（@ConditionalOnMissingBean 的意义就在这）
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>「默认」的含义就是 @ConditionalOnMissingBean：以后接真实场景服务时，
 *       新写一个同类型的 Bean 覆盖即可，调用方一行不用改。</li>
 *   <li>用 @Bean 方法产出，而不是在 InMemoryScenarioRepository 上打 @Component：
 *       这样「这只是兜底实现」的存在感只留在装配处，替换时不必去改那个实现类。</li>
 *   <li>proxyBeanMethods = false：本类只有一个 Bean 方法、方法之间没有相互调用，
 *       关掉 CGLIB 代理可以少生成一个子类。</li>
 *   <li>注：评测策略（AssessStrategy）随训练域一起暂未迁入，
 *       待「口语训练回答策略」落地时再加到这里。</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
public class DefaultBeansConfiguration {

    /**
     * 默认的口语训练场景目录（内置的自由对话 + 英文面试）。
     * 流程：只有当容器里还没有 IScenarioRepository 时才生效，接真实场景服务时覆盖它。
     */
    @Bean
    @ConditionalOnMissingBean(IScenarioRepository.class)
    public IScenarioRepository defaultScenarioRepository() {
        return new InMemoryScenarioRepository();
    }
}

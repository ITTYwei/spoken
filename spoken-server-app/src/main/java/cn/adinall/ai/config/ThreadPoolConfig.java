package cn.adinall.ai.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

import java.util.concurrent.*;

/**
 * 线程池配置（通用 ThreadPoolExecutor Bean + 打开 @EnableAsync）
 * <p>
 * 功能：把 thread.pool.executor.config.* 绑定出来的参数拼成一个 ThreadPoolExecutor，
 * 并打开 Spring 的异步执行能力，供业务侧按需使用。
 * <pre>
 *   threadPoolExecutor(properties)  按配置造线程池：核心 / 最大线程数、等待队列、拒绝策略
 * </pre>
 * 运行过程：
 * <pre>
 *   Spring 容器启动
 *        |
 *        | (1) @EnableConfigurationProperties(ThreadPoolConfigProperties.class) 绑定
 *        |     thread.pool.executor.config.{core-pool-size, max-pool-size,
 *        |                                  keep-alive-time, block-queue-size, policy}
 *        |
 *        | (2) @ConditionalOnMissingBean(ThreadPoolExecutor.class)：业务侧已有自定义线程池则不创建
 *        |
 *        | (3) switch (properties.getPolicy())
 *        |       "AbortPolicy"         -> AbortPolicy（默认分支也是它）
 *        |       "DiscardPolicy"       -> DiscardPolicy
 *        |       "DiscardOldestPolicy" -> DiscardOldestPolicy
 *        |       "CallerRunsPolicy"    -> CallerRunsPolicy
 *        |
 *        | (4) new ThreadPoolExecutor(corePoolSize, maxPoolSize, keepAliveTime, TimeUnit.SECONDS,
 *        |                            new LinkedBlockingQueue&lt;&gt;(blockQueueSize),
 *        |                            Executors.defaultThreadFactory(), handler)
 *        v
 *   容器里出现 bean "threadPoolExecutor"
 * </pre>
 * 案例：
 * <pre>
 *   输入: thread.pool.executor.config:
 *           core-pool-size: 20
 *           max-pool-size: 200
 *           block-queue-size: 5000
 *           policy: CallerRunsPolicy
 *   处理: 造出「核心 20 / 最大 200 / 队列 5000 / keepAlive 10 秒 / 主线程自己跑」的线程池
 *   输出: bean "threadPoolExecutor"；@Async("threadPoolExecutor") 标注的方法跑在它上面
 *         策略名拼错（如 policy: Abort）--> 走 default 分支，仍是 AbortPolicy，不报错也不提示
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>队列容量（默认 5000）远大于核心线程数：ThreadPoolExecutor 只有队列满了才会把线程扩到
 *       maxPoolSize，所以队列没满之前「最大线程数」形同虚设 —— 调参先看队列。</li>
 *   <li>@ConditionalOnMissingBean(ThreadPoolExecutor.class) 允许业务侧自带线程池覆盖本 Bean，
 *       代价是「谁最终生效」取决于容器里谁先注册，排查时先看 Bean 的来源。</li>
 *   <li>拒绝策略按字符串匹配且有 default 分支：yml 写错策略名不会启动失败，而是静默退回
 *       AbortPolicy（后果是任务被拒时抛 RejectedExecutionException），配置要照常量名写。</li>
 *   <li>Bean 名是方法名 threadPoolExecutor：@Async 默认只找 TaskExecutor / 名为 taskExecutor 的
 *       Bean，要用这个池子请写 @Async("threadPoolExecutor") 显式指定。</li>
 *   <li>方法签名上的 ClassNotFoundException / InstantiationException / IllegalAccessException
 *       是早期用反射造对象的遗留：现在方法体里没有反射，重构时可以一起删掉。</li>
 * </ul>
 */
@Slf4j
@EnableAsync
@Configuration
@EnableConfigurationProperties(ThreadPoolConfigProperties.class)
public class ThreadPoolConfig {

    /** 创建执行后台任务的线程池。 */
    @Bean
    @ConditionalOnMissingBean(ThreadPoolExecutor.class)
    public ThreadPoolExecutor threadPoolExecutor(ThreadPoolConfigProperties properties) throws ClassNotFoundException, InstantiationException, IllegalAccessException {
        // 实例化策略
        RejectedExecutionHandler handler;
        switch (properties.getPolicy()){
            case "AbortPolicy":
                handler = new ThreadPoolExecutor.AbortPolicy();
                break;
            case "DiscardPolicy":
                handler = new ThreadPoolExecutor.DiscardPolicy();
                break;
            case "DiscardOldestPolicy":
                handler = new ThreadPoolExecutor.DiscardOldestPolicy();
                break;
            case "CallerRunsPolicy":
                handler = new ThreadPoolExecutor.CallerRunsPolicy();
                break;
            default:
                handler = new ThreadPoolExecutor.AbortPolicy();
                break;
        }
        // 创建线程池
        return new ThreadPoolExecutor(properties.getCorePoolSize(),
                properties.getMaxPoolSize(),
                properties.getKeepAliveTime(),
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(properties.getBlockQueueSize()),
                Executors.defaultThreadFactory(),
                handler);
    }

}

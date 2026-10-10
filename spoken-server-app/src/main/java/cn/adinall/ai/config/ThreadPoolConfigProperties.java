package cn.adinall.ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 线程池参数（@ConfigurationProperties，前缀 thread.pool.executor.config）
 * <p>
 * 功能：把 yml 里这段配置绑成强类型对象交给 ThreadPoolConfig 造线程池；一个字段都不写就是下面这套默认值。
 * <pre>
 *   corePoolSize    核心线程数，默认 20
 *   maxPoolSize     最大线程数，默认 200
 *   keepAliveTime   空闲线程存活时间，单位秒，默认 10
 *   blockQueueSize  等待队列容量，默认 5000
 *   policy          拒绝策略名，默认 "AbortPolicy"
 * </pre>
 * 运行过程：
 * <pre>
 *   application.yml
 *        thread.pool.executor.config.{core-pool-size | max-pool-size |
 *                                     keep-alive-time | block-queue-size | policy}
 *        |
 *        v
 *   Spring Boot 宽松绑定（kebab-case -> 驼峰字段），未知字段按 ignoreInvalidFields 忽略
 *        |
 *        v
 *   ThreadPoolConfigProperties（本类；@Data 生成 getter 供读取）
 *        |
 *        v
 *   ThreadPoolConfig.threadPoolExecutor(properties) 造出 ThreadPoolExecutor
 * </pre>
 * 案例：
 * <pre>
 *   输入: thread.pool.executor.config:
 *           core-pool-size: 50
 *           policy: DiscardPolicy
 *   处理: 没写的字段保持类里的默认值
 *   输出: getCorePoolSize()==50，getPolicy()=="DiscardPolicy"，
 *         getKeepAliveTime()==10，getBlockQueueSize()==5000，getMaxPoolSize()==200
 *   一个字段都不写: 20 / 200 / 10 / 5000 / "AbortPolicy"
 *   写了不存在的字段（如 queue-size）: 静默忽略，不阻塞启动
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>字段用包装类型（Integer / Long）而不是基本类型：避免「显式配了空值」时被覆盖成 0，
 *       也不会在造线程池时拿到 0 这种直接抛 IllegalArgumentException 的参数。</li>
 *   <li>ignoreInvalidFields = true：拼错字段名不会让应用起不来，代价是拼错的配置静默失效 ——
 *       调参后要确认真实生效的线程池参数，而不是只看 yml。</li>
 *   <li>policy 用字符串而不是枚举：写错时 ThreadPoolConfig 会退回 AbortPolicy，
 *       校验放在这一层反而会新增一个启动失败点。</li>
 *   <li>字段名（kebab-case）就是对外契约：改字段名等于改所有部署环境的配置，
 *       新增参数只追加、不重命名。</li>
 * </ul>
 */
@Data
@ConfigurationProperties(prefix = "thread.pool.executor.config", ignoreInvalidFields = true)
public class ThreadPoolConfigProperties {

    /** 核心线程数 */
    private Integer corePoolSize = 20;
    /** 最大线程数 */
    private Integer maxPoolSize = 200;
    /** 最大等待时间 */
    private Long keepAliveTime = 10L;
    /** 最大队列数 */
    private Integer blockQueueSize = 5000;
    /*
     * AbortPolicy：丢弃任务并抛出RejectedExecutionException异常。
     * DiscardPolicy：直接丢弃任务，但是不会抛出异常
     * DiscardOldestPolicy：将最早进入队列的任务删除，之后再尝试加入队列的任务被拒绝
     * CallerRunsPolicy：如果任务添加线程池失败，那么主线程自己执行该任务
     * */
    private String policy = "AbortPolicy";

}

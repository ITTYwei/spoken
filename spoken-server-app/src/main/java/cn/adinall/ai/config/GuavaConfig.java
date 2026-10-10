package cn.adinall.ai.config;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * 本地缓存配置（Guava Cache，写后 3 秒过期）
 * <p>
 * 功能：往容器里放一个名为 "cache" 的字符串缓存，给「短时间内的重复请求 / 重复事件」做去重与防抖。
 * <pre>
 *   cache()  构造 Cache&lt;String, String&gt;：CacheBuilder.newBuilder().expireAfterWrite(3, SECONDS).build()
 * </pre>
 * 运行过程：
 * <pre>
 *   Spring 容器启动
 *        |
 *        v
 *   GuavaConfig.cache()
 *        |
 *        | CacheBuilder.newBuilder().expireAfterWrite(3, TimeUnit.SECONDS).build()
 *        v
 *   容器里出现 bean "cache"（类型 Cache&lt;String, String&gt;）
 *        |
 *        | 取用方：@Resource(name = "cache") 注入（当前仓库内还没有注入点，属预置 Bean）
 *        v
 *   cache.put("k","v") --> 3 秒内 getIfPresent("k") 命中；满 3 秒后返回 null
 * </pre>
 * 案例：
 * <pre>
 *   输入: cache.put("session:s-001:last", "hello")
 *   处理: 立刻 cache.getIfPresent("session:s-001:last")
 *   输出: "hello"
 *         写入满 3 秒后再取: null（Guava 按写入时间过期，读的时候顺手清理）
 *         不存在的键: null（不是异常）
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>3 秒是「时间窗口」而不是业务缓存时长：它只够挡住同一请求的重试 / 重放，
 *       拿它当数据缓存会出现「刚写的值立刻读不到」的偶发问题。</li>
 *   <li>bean 名固定为 "cache"：取用方按名字注入（@Resource(name = "cache")），
 *       改名会让所有注入点起不来。</li>
 *   <li>Guava Cache 本身线程安全，但它是单进程内存：多实例部署时每进程一份，
 *       需要全局一致的场景要换 Redis 之类的共享存储。</li>
 *   <li>expireAfterWrite 而不是 expireAfterAccess：要的是「写入后固定窗口内有效」，
 *       与访问频率无关，才适合做去重。</li>
 * </ul>
 */
@Configuration
public class GuavaConfig {

    @Bean(name = "cache")
    public Cache<String, String> cache() {
        return CacheBuilder.newBuilder()
                .expireAfterWrite(3, TimeUnit.SECONDS)
                .build();
    }

}

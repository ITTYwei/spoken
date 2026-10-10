package cn.adinall.ai.infrastructure.config;

import cn.adinall.ai.domain.shared.adapter.port.IAppConfigPort;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 应用配置（@ConfigurationProperties(prefix = "spoken-ai")，同时实现领域端口 {@link IAppConfigPort}）
 * <p>
 * 功能：绑定 application.yml 里的 spoken-ai.* 配置项，并以领域端口的形式交给用例层。
 * <pre>
 *   maxSessionsPerDevice()    同一设备最多几个会话，默认 1（上游单 GPU 槽）
 *   readyWaitMs()             建会话后等后端就绪的最长阻塞时间，默认 15000
 *   sessionIdleTimeoutMs()    设备多久没事件算会话已死，默认 300000
 *   slotReleaseSettleMs()     关旧会话到建新会话之间的额外等待，默认 800
 *   corsAllowedOrigins()      允许跨域的来源，默认 ["*"]
 * </pre>
 * 运行过程（yml -> Bean -> 消费者）：
 * <pre>
 *   application.yml:  spoken-ai.ready-wait-ms: 15000
 *        |
 *        v
 *   PersistenceConfiguration: @EnableConfigurationProperties(AppProperties.class)
 *        |  Spring 松绑定，逐个调用 setter
 *        v
 *   AppProperties（本类）
 *        |
 *        +--> 作为 IAppConfigPort 注入 ConversationCaseImpl
 *        |        | (1) sleepQuietly(appConfig.slotReleaseSettleMs())   关旧会话后让位
 *        |        | (2) deadline = now + appConfig.readyWaitMs()        轮询等 READY
 *        |
 *        +--> 注入 WebCorsConfiguration
 *                 allowedOriginPatterns(appConfig.corsAllowedOrigins()...)
 * </pre>
 * 案例：
 * <pre>
 *   输入: yml 只写 spoken-ai.slot-release-settle-ms=800，其余不配
 *   处理: 绑定时只调用 setSlotReleaseSettleMs(800)，其它字段保留字段初值
 *   输出: IAppConfigPort 的五个方法分别返回
 *         1 / 15000 / 300000 / 800 / ["*"]；
 *         ConversationCaseImpl 关掉旧会话后 sleep(800) 再建新会话，
 *         降低撞上「GPU 槽位还没释放」的概率
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>同时承担两个角色：绑定 YAML + 实现 {@link IAppConfigPort}，
 *       让用例层不必知道配置是从 YAML、环境变量还是配置中心来的。</li>
 *   <li>默认值与历史版本保持一致 —— 改默认值等于改运行时行为：
 *       例如 slotReleaseSettleMs 调成 0，会明显提高「新建会话就撞槽位忙」的概率。</li>
 *   <li>类上没有 @Component，靠 PersistenceConfiguration 的 @EnableConfigurationProperties 注册；
 *       把标注留在装配处，将来换配置来源（配置中心等）时只改一处。</li>
 *   <li>maxSessionsPerDevice 与 sessionIdleTimeoutMs 目前是留作后续的值：
 *       当前实现是建会话前无条件关掉旧会话、也没有回收线程，
 *       读代码时别以为这两个值已经参与调度。</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "spoken-ai")
public class AppProperties implements IAppConfigPort {

    /** 同一设备最多同时存在几个会话（数字人后端是单槽调度，默认 1 最省事）。 */
    private int maxSessionsPerDevice = 1;

    /** 建会话后等待后端就绪的最长阻塞时间（毫秒）。超时就返回 QUEUED 让 App 轮询/等 SSE。 */
    private long readyWaitMs = 15_000L;

    /** 设备多久没任何事件就认为会话已死，可被回收（毫秒）。 */
    private long sessionIdleTimeoutMs = 300_000L;

    /**
     * 关掉老会话后、建新会话前额外等待的时间（毫秒）。
     * 数字人后端是单 GPU 槽，槽位释放是异步的，等一小会儿能显著降低「新建会话就撞槽位忙」的概率。
     */
    private long slotReleaseSettleMs = 800L;

    /** 允许跨域的来源，供 WebCorsConfiguration 装配 CORS 规则。 */
    private List<String> corsAllowedOrigins = new ArrayList<>(List.of("*"));

    @Override
    public int maxSessionsPerDevice() {
        return maxSessionsPerDevice;
    }

    public void setMaxSessionsPerDevice(int maxSessionsPerDevice) {
        this.maxSessionsPerDevice = maxSessionsPerDevice;
    }

    @Override
    public long readyWaitMs() {
        return readyWaitMs;
    }

    public void setReadyWaitMs(long readyWaitMs) {
        this.readyWaitMs = readyWaitMs;
    }

    @Override
    public long sessionIdleTimeoutMs() {
        return sessionIdleTimeoutMs;
    }

    public void setSessionIdleTimeoutMs(long sessionIdleTimeoutMs) {
        this.sessionIdleTimeoutMs = sessionIdleTimeoutMs;
    }

    @Override
    public long slotReleaseSettleMs() {
        return slotReleaseSettleMs;
    }

    public void setSlotReleaseSettleMs(long slotReleaseSettleMs) {
        this.slotReleaseSettleMs = slotReleaseSettleMs;
    }

    @Override
    public List<String> corsAllowedOrigins() {
        return corsAllowedOrigins;
    }

    public void setCorsAllowedOrigins(List<String> corsAllowedOrigins) {
        this.corsAllowedOrigins = corsAllowedOrigins;
    }
}
package cn.adinall.ai.infrastructure.config;

import cn.adinall.ai.domain.business.shared.adapter.port.IAppConfigPort;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 应用配置（实现领域层声明的配置端口）。
 *
 * 这里同时承担两个角色：
 * 1. 绑定 application.yml 里的 spoken-ai.* 配置项；
 * 2. 实现 {@link IAppConfigPort}，让用例层不必知道配置是从 YAML、环境变量还是配置中心来的。
 *
 * 默认值与历史版本保持一致 —— 改动默认值等于改了运行时行为。
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
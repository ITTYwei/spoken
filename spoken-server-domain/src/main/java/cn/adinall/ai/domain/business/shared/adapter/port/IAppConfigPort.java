package cn.adinall.ai.domain.business.shared.adapter.port;

import java.util.List;

/**
 * 应用配置端口（公共内核）。
 *
 * 为什么配置也要走端口：用例编排层需要知道「等上游就绪最多等多久」「关旧会话后等多久再建新的」
 * 这类策略，但这些值来自配置文件（属于基础设施）。让编排层直接读 application.yml
 * 等于把配置机制焊死在业务逻辑上 —— 于是这里只声明「我需要这几个值」，
 * 由基础设施层的 @ConfigurationProperties 实现它。
 *
 * 注意：领域模型（聚合、策略）不依赖这个端口。会用到它的只有编排层和启动配置。
 */
public interface IAppConfigPort {

    /** 建会话后等待后端就绪的最长阻塞时间（毫秒）。超时就返回 QUEUED 让 App 轮询/等 SSE。 */
    long readyWaitMs();

    /**
     * 关掉老会话后、建新会话前额外等待的时间（毫秒）。
     * 数字人后端是单 GPU 槽，槽位释放是异步的，等一小会儿能显著降低「新建会话就撞槽位忙」的概率。
     */
    long slotReleaseSettleMs();

    /**
     * 同一设备最多同时存在几个会话。
     * 当前实现是「单设备单会话」（建会话前无条件关掉旧会话），这个值留作后续做多会话调度时使用。
     */
    int maxSessionsPerDevice();

    /** 设备多久没任何事件就认为会话已死、可被回收（毫秒）。同样暂未启用回收线程。 */
    long sessionIdleTimeoutMs();

    /** 允许跨域的来源列表，启动配置用它装配 CORS 规则。 */
    List<String> corsAllowedOrigins();
}
package cn.adinall.ai.domain.coaching.conversation.model.valobj;

/**
 * 会话生命周期枚举（与具体数字人后端解耦的状态取值）
 * <p>
 * 功能：声明一场会话可能处于的全部状态；由驱动适配器负责把上游状态文案映射成这里的取值。
 * <pre>
 *   QUEUED    已受理，但后端资源（GPU 槽位）没空，正在排队
 *   READY     后端已就绪，可以交换 SDP
 *   ACTIVE    正在使用中（已建立媒体连接）
 *   CLOSING   客户端已请求结束
 *   CLOSED    已关闭
 *   ERROR     出错（聚合上同时记 errorMessage）
 * </pre>
 * 状态流转（触发方法见 ConversationSessionAggregate）：
 * <pre>
 *   QUEUED --markReady() / markStatus(READY)--------> READY
 *   READY  --markActive() / markStatus(ACTIVE)------> ACTIVE
 *   READY / ACTIVE --markClosing()------------------> CLOSING --markClosed()--> CLOSED
 *   未收尾态 --markError(String)--------------------> ERROR
 *   isFinished() = CLOSING / CLOSED / ERROR：不能再换 SDP、不能再上行语音
 * </pre>
 * 案例：
 * <pre>
 *   输入: 上游建会话第一次回 "queued"
 *   处理: 适配器映射成 QUEUED --> 聚合 status = QUEUED（SessionHandleVO.status）
 *         App 轮询 --> ConversationServiceImpl.refreshStatus -->
 *         avatarDriver.status("ot-7f3a") 回 READY --> session.markStatus(READY)
 *   输出: getStatus() = READY，此时 canExchangeOffer() = true，exchangeOffer 才会成功
 *   异常分支: 上游回 CLOSED --> isFinished() = true --> exchangeOffer 抛 session_not_found，
 *             transcribeAndRecord 抛 session_not_open
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>状态取值与具体后端无关：上游的 queued / worker_ready / running 等文案只在适配器里翻译，
 *       换供应商时领域代码和这个枚举都不用动。</li>
 *   <li>CLOSING 与 CLOSED 分开是给「客户端已请求结束、但上游资源还没放」留出可观测的中间态；
 *       合成一个值就分不清「还要不要等槽位释放」，而这正是用例层 sleep 的依据。</li>
 *   <li>isFinished() 把 CLOSING 也算收尾：一旦客户端提出结束，就不该再放行换 SDP 或上行语音，
 *       否则会和正在关闭的会话抢上游资源。</li>
 * </ul>
 */
public enum SessionStatusEnum {
    /** 已受理，但后端资源（GPU 槽位）没空，正在排队 */
    QUEUED,
    /** 后端已就绪，可以交换 SDP */
    READY,
    /** 正在使用中（已建立媒体连接） */
    ACTIVE,
    /** 客户端已请求结束 */
    CLOSING,
    CLOSED,
    ERROR
}
package cn.adinall.ai.domain.shared.adapter.port;

import cn.adinall.ai.domain.conversation.model.valobj.LlmOwnerEnum;

import java.util.List;

/**
 * 应用配置端口（公共内核：把配置值当依赖注入业务，而不是让业务直接读 yml）
 * <p>
 * 功能：向用例编排层声明「我需要这几个策略值」，由基础设施层的配置类
 * （AppProperties，绑定 spoken-ai.*）实现它。
 * <pre>
 *   readyWaitMs()            建会话后等后端就绪的最长阻塞时间（默认 15000ms）
 *   slotReleaseSettleMs()    关旧会话后、建新会话前额外等的时间（默认 800ms）
 *   maxSessionsPerDevice()   同一设备最多同时存在几个会话（默认 1，当前是单设备单会话）
 *   sessionIdleTimeoutMs()   多久没事件就认为会话已死、可被回收（默认 300000ms，暂未启用回收线程）
 *   corsAllowedOrigins()     允许跨域的来源列表（默认 ["*"]），启动配置用它装配 CORS 规则
 * </pre>
 * 数据流：
 * <pre>
 *   application.yml（spoken-ai.*）+ AppProperties
 *        |
 *        v
 *   IAppConfigPort（本接口）
 *        |
 *        v
 *   ConversationCaseImpl.create
 *        +-- closeSessionsOfDevice 返回 true 后 sleepQuietly(slotReleaseSettleMs())
 *        +-- awaitReady: deadline = now + readyWaitMs()，每 400ms refreshStatus 一次
 *        v
 *   超时不算失败：把 status=QUEUED 的会话返回给 App，让它继续轮询或听 SSE
 * </pre>
 * 案例：
 * <pre>
 *   输入: 设备 dev-001 已有一场会话，App 再次 POST /api/v1/sessions
 *   处理: closeSessionsOfDevice("dev-001") = true --> sleepQuietly(800L)
 *         --> createSession(...) --> awaitReady 最多轮询 15000ms（每 400ms 一次）
 *   输出: 上游在预算内就绪 --> 返回 status=READY 的会话
 *         上游一直排队 --> 返回 status=QUEUED（不抛错），App 继续轮询或等 session.ready
 *   对照: 没有旧会话时 closeSessionsOfDevice 返回 false，800ms 那次等待被跳过
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>让编排层直接读 application.yml 等于把配置机制焊死在业务逻辑上：走端口之后，
 *       单测里给一个假实现就能构造「就绪超时」「槽位一直忙」这些分支，不必改配置文件。</li>
 *   <li>领域模型（聚合、策略）刻意不依赖这个端口 —— 会话状态机不该因为「等多久」这种
 *       环境相关的量而变化；会用到它的只有编排层和启动配置。</li>
 *   <li>maxSessionsPerDevice / sessionIdleTimeoutMs 目前是预留值（回收线程尚未启用）：
 *       声明在这里是为了让「以后要做多会话调度 / 空闲回收」有明确的接线位置，
 *       调用方不要以为它们已经在生效。</li>
 *   <li>默认值与历史版本保持一致（改动默认值等于改了运行时行为）：
 *       15000ms / 800ms / 1 / 300000ms / ["*"] 都是当前实际生效的值。</li>
 * </ul>
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

    /**
     * 谁产出数字人的回答文本（对应配置 spoken-ai.llm-owner）。
     *
     * <p>这是「评测能不能插进对话链路」的总开关：
     * <ul>
     *   <li>{@link LlmOwnerEnum#AGENT}（默认）—— Java 自己跑智能体，于是能在 LLM 之前插入发音评测
     *       （用户语音 → 评测 → 拼进提示词 → agent 生成 → 整段 /say）；</li>
     *   <li>{@link LlmOwnerEnum#OPENTALKING} —— 把音频交给上游一条龙，行为与迁移前一致。</li>
     * </ul>
     *
     * 放在配置端口而不是写死在用例里：这两条路径要能随时切换做对比与降级，
     * 而「当前用哪条」是部署决策，不是业务规则。
     */
    LlmOwnerEnum llmOwner();

    /** 设备多久没任何事件就认为会话已死、可被回收（毫秒）。同样暂未启用回收线程。 */
    long sessionIdleTimeoutMs();

    /** 允许跨域的来源列表，启动配置用它装配 CORS 规则。 */
    List<String> corsAllowedOrigins();
}
package cn.adinall.ai.domain.coaching.conversation.model.valobj;

/**
 * 上游会话句柄值对象（驱动后端建会话后的返回）
 * <p>
 * 功能：把「上游的会话标识 + 初始状态」带回领域；领域只负责原样保存、原样带回去。
 * <pre>
 *   providerSessionId  上游会话标识（OpenTalking 的 session_id），领域不理解它的内部结构
 *   status             建会话那一刻的状态，通常是 QUEUED（排队）或 READY（已就绪）
 * </pre>
 * 运行过程：
 * <pre>
 *   IAvatarDriverPort.createSession(CreateSessionCommandEntity)
 *        |
 *        v
 *   SessionHandleVO{providerSessionId, status}
 *        |
 *        | ConversationServiceImpl.createSession
 *        |   new ConversationSessionAggregate(newSessionId(), handle.providerSessionId(),
 *        |           command.deviceId(), ..., handle.status())
 *        v
 *   ConversationSessionAggregate
 *        +-- sessionId         我方 id（s- 开头，给 App 用）
 *        +-- providerSessionId 上游 id（status / exchangeOffer / speak 都要带上）
 *        +-- status            状态机的起点
 * </pre>
 * 案例：
 * <pre>
 *   输入: avatarDriver.createSession(new CreateSessionCommandEntity("anchor", "flashhead",
 *             "en-US-JennyNeural", null))，此时上游 GPU 槽被别的会话占着
 *   处理: 适配器把上游 "session_id":"ot-7f3a" 与排队状态映射成领域类型
 *   输出: SessionHandleVO{providerSessionId="ot-7f3a", status=QUEUED}
 *         --> 聚合 sessionId="s-9c1d2f3e"、status=QUEUED；用例层随后轮询 refreshStatus
 *   就绪后: avatarDriver.status("ot-7f3a") 回 READY --> session.markStatus(READY)
 *         --> canExchangeOffer() = true，App 才能换 SDP
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>providerSessionId 与聚合上的 sessionId 分成两个字段而不是复用：我方 id 要对 App 稳定、
 *       可换后端；上游 id 会随供应商变化，两者一旦混用，换后端时历史数据就没法解释。</li>
 *   <li>句柄不带上游的内部细节（worker 地址、鉴权 token 等）：领域只需要一个「原样带回去」的
 *       字符串，多存一份上游状态就多一份要同步的信息。</li>
 *   <li>status 允许是 QUEUED：适配器如实映射排队状态，不在内部阻塞等待；
 *       「等多久」由用例层 awaitReady 决定，超时也不影响「这次建会话已经成功」这个事实。</li>
 * </ul>
 */
public record SessionHandleVO(String providerSessionId, SessionStatusEnum status) {
}
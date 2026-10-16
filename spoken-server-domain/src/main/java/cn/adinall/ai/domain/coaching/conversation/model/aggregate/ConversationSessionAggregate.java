package cn.adinall.ai.domain.coaching.conversation.model.aggregate;

import cn.adinall.ai.domain.coaching.conversation.model.valobj.SessionStatusEnum;
import cn.adinall.ai.types.enums.ResponseCodeEnum;
import cn.adinall.ai.types.exception.AppException;

import java.util.UUID;

/**
 * 会话聚合根（本系统里唯一真正带状态机的领域对象）
 * <p>
 * 功能：把三件必须一起变的事收在一个对象里 —— 会话生命周期、数字人回复的流式缓冲、
 * 「用户刚说完话」的时刻。
 * <pre>
 *   newSessionId()                 生成我方会话 id（s- 开头）
 *   isQueued() / isFinished()      排队中？已收尾？（CLOSING / CLOSED / ERROR 都算收尾）
 *   canExchangeOffer()             现在能不能换 SDP
 *   markReady() / markActive() / markClosing() / markClosed() / markError(String)
 *   markStatus(SessionStatusEnum)  按上游纠偏状态
 *   requireOpen()                  不处于收尾态才放行，否则抛 session_not_open
 *   markOpeningSpoken() / isOpeningSpoken()   幂等：开场白只有第一次返回 true
 *   touch() / idleMillis()         记一次「还活着」 / 算了空闲了多久
 *   appendAssistantDelta(String) / finishAssistant(String)
 *   noteUserSpeechEnded() / isNewUserText(String) / lastUserText(String) / responseLatencyMs()
 * </pre>
 * 状态迁移（SessionStatusEnum；箭头左边是触发方法）：
 * 下面是**正常路径**的走法。代码里**没有任何迁移守卫**：markReady / markActive / markClosing /
 * markClosed / markError 都是无条件赋值，markStatus(SessionStatusEnum) 也接受任意枚举值，
 * 所以非正常迁移不会被拒绝，靠调用方按下面的路径走。
 * <pre>
 *   markReady() / markStatus(READY)      : QUEUED  --> READY
 *   markActive() / markStatus(ACTIVE)    : READY   --> ACTIVE
 *   markClosing()                        : READY / ACTIVE --> CLOSING
 *   markClosed() / markStatus(CLOSED)    : 未收尾态 --> CLOSED
 *   markError(String)                    : 任意未收尾态 --> ERROR（同时记 errorMessage）
 *   isFinished() = CLOSING / CLOSED / ERROR：不能再换 SDP、不能再上行语音
 * </pre>
 * 案例：
 * <pre>
 *   输入: new ConversationSessionAggregate("s-9c1d2f3e", "ot-7f3a", "dev-001", "anchor",
 *             "flashhead", "en-US-JennyNeural", "interview", SessionStatusEnum.QUEUED)
 *   处理: markOpeningSpoken() --> true（第二次并发/重复调用返回 false，开场白不说两遍）
 *         markStatus(READY)   --> status = READY，canExchangeOffer() = true
 *         appendAssistantDelta("Hi! I'm ") + appendAssistantDelta("Amy.")
 *         finishAssistant("Hi! I'm Amy.") --> 返回整段并清空缓冲
 *   输出: getStatus() = READY；isFinished() = false；没说过话前 responseLatencyMs() = null
 *         之后 markClosed() --> isFinished() = true，再 requireOpen() 抛 session_not_open
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>它把三件事收在一起，因为它们必须一起变：生命周期（QUEUED -> READY -> ... -> CLOSED / ERROR）、
 *       数字人回复的流式缓冲（delta 累加到 done 收尾，中间可能被用户打断）、
 *       以及「用户刚说完话」的时刻（用它算数字人的响应延迟）。</li>
 *   <li>刻意不放进来的东西：事件总线（Sinks）是「怎么把事件送出去」，属于基础设施；
 *       provider 会话句柄的细节只知道要原样带回去，不需要理解它 —— 聚合因此不依赖任何框架类型。</li>
 *   <li>字段可变、方法带副作用，它不是 DTO：把状态迁移收进方法里，比让外部随手 setStatus
 *       更容易看清「谁有权把会话推到 CLOSED」。</li>
 *   <li>openingSpoken / lastUserText / assistantBuffer 都放内存不放库：会话本身就是进程内的
 *       （重启后 sessionId 一律查不到），落库对「防重放」没有任何帮助，反而多一份要同步的状态。</li>
 *   <li>markOpeningSpoken 加 synchronized 不是防御性编程：用户连点两次「开始对话」或客户端超时重试
 *       会真的并发走到这里，重复开场的代价是数字人把第一句话说两遍，而且不可撤销。</li>
 *   <li>canExchangeOffer 除 QUEUED 外还挡收尾态（CLOSING / CLOSED / ERROR）：上游在 WebRTC 断开后
 *       会自动关会话并释放 GPU 槽位，那个 providerSessionId 已经作废，不该再发起 SDP 交换。
 *       它的唯一调用点是 ConversationServiceImpl.exchangeOffer，调用点先用 isFinished() 判收尾态
 *       --> 抛 404（session_not_found），再判 canExchangeOffer --> 排队态抛 409（session_queued），
 *       两种情况都不会真的往上发 SDP 请求。</li>
 * </ul>
 */
public class ConversationSessionAggregate {

    /** 创建时捕获的会话主体；后续登录变化不能替换已有归属。 */
    private String subject;

    /** 首次绑定会话主体；身份同时用于历史、课程、工具和教学证据隔离。 */
    public synchronized void bindSubject(String subject) {
        if (this.subject == null) this.subject = subject;
    }

    /** 返回冻结的主体；未绑定账号的会话保持设备隔离。 */
    public synchronized String subject() {
        return subject == null ? "device:" + deviceId : subject;
    }

    /** 本系统业务会话标识。 */ private final String sessionId;
    /** 数字人服务会话句柄。 */ private final String providerSessionId;
    /** 创建会话的匿名设备标识。 */ private final String deviceId;
    /** 请求的数字人形象标识。 */ private final String avatarId;
    /** 创建时选择的渲染模式。 */ private final String model;
    /** 本会话默认TTS音色。 */ private final String voice;
    /** 这场会话按哪个剧本走（见 ScenarioVO）。决定开场白说什么。 */
    private final String scenarioId;
    /** 会话运行态创建时刻，用于业务响应；数据库审计字段使用DATETIME。 */ private final long createdAt;

    /** 当前业务生命周期状态。 */ private SessionStatusEnum status;
    /** 最近活动时间，用于空闲回收。 */ private long lastEventAt;
    /** 生命周期失败原因。 */ private String errorMessage;

    /**
     * 开场白是否已经说过。
     *
     * 放内存不放库：会话本身就是进程内的（重启后 sessionId 一律查不到），
     * 把它落库对"防重放"没有任何帮助，反而多一份要同步的状态。
     * 和 lastUserText / assistantBuffer 同类：都是**这一场会话的运行态**。
     */
    private boolean openingSpoken;
    /** 创建时确定的首句决策；包含初始档位，跨午夜及重复请求不重算。 */
    private volatile cn.adinall.ai.domain.coaching.conversation.model.valobj.OpeningDecisionVO openingDecision;

    /** 首次写入后冻结；并发补写时所有调用方使用同一份已冻结决策。 */
    public synchronized cn.adinall.ai.domain.coaching.conversation.model.valobj.OpeningDecisionVO freezeOpening(
            cn.adinall.ai.domain.coaching.conversation.model.valobj.OpeningDecisionVO decision) {
        if (openingDecision == null) openingDecision = java.util.Objects.requireNonNull(decision);
        return openingDecision;
    }

    /** 读取冻结的开场决策。 */
    public cn.adinall.ai.domain.coaching.conversation.model.valobj.OpeningDecisionVO getOpeningDecision() { return openingDecision; }
    /** 创建阶段仅可补齐一次课程询问；完成后原文冻结，开场翻译和重试都读取同一快照。 */
    private boolean openingPreparationCompleted;
    /** 必须在返回新会话、媒体可播放之前调用；已认领开场后拒绝任何改写。 */
    public synchronized cn.adinall.ai.domain.coaching.conversation.model.valobj.OpeningDecisionVO completeOpeningPreparation(String text) {
        if(openingPreparationCompleted || openingSpoken) throw new IllegalStateException("opening_already_prepared");
        openingPreparationCompleted=true;
        openingDecision=openingDecision.withText(text);
        return openingDecision;
    }

    /** 用户最后一次说完话的时刻；用来算「数字人隔了多久才开口」。 */
    private long lastUserSpeechEndedAt;
    /** 最近一句用户台词。上游可能把同一条 stt.final 推两遍，靠它去重。 */
    private String lastUserText;
    /** 数字人这一轮的流式文本缓冲，done 时清空。 */
    private final StringBuilder assistantBuffer = new StringBuilder();
    /** 当前可接收媒体事件的回复；取消后保留失效标识，拒收旧 SSE。 */
    private volatile String activeReplyId;

    /** 回复开始时固定归属；新用户输入不能改变正在播报回复的回合编号。 */
    private Long replyTurnId;
    private boolean replyTurnBound;
    private boolean replyCompleted;
    private String receiptId = UUID.randomUUID().toString();
    /** 返回当前回复的归档去重标识。 */
    public synchronized String replyReceiptId() { return receiptId; }
    /** 判断当前回复是否已经完成归档与状态提交。 */
    public synchronized boolean replyCompleted() { return replyCompleted; }
    /** 合并完成文本与已接收的字幕，读取时不清除缓冲。 */
    public synchronized String assistantText(String finalText) {
        return finalText == null || finalText.isBlank() ? assistantBuffer.toString() : finalText;
    }

    /** 将本次回复固定绑定到对应用户回合，空值表示独立助手回合。 */
    public synchronized void bindReplyTurn(Long turnId) { replyTurnId = turnId; replyTurnBound = true; }
    /** 返回本次回复绑定的用户回合标识，可为空。 */
    public synchronized Long replyTurnId() { return replyTurnId; }
    /** 判断本次回复是否已经确定回合绑定关系。 */
    public synchronized boolean replyTurnBound() { return replyTurnBound; }
    /** 原子登记当前回复完成；重复完成返回失败。 */
    public synchronized boolean completeReplyOnce() {
        if (replyCompleted) return false;
        replyCompleted = true;
        return true;
    }

    /** 开始新回复，重置完成标志、回合绑定和文本缓冲。 */
    public synchronized void beginReply(String replyId) {
        activeReplyId = replyId;
        receiptId = replyId == null ? UUID.randomUUID().toString() : replyId;
        replyTurnId = null; replyTurnBound = false; replyCompleted = false;
        assistantBuffer.setLength(0);
    }

    /** 判断回复标识是否仍属于当前有效轮次。 */
    public boolean acceptsReply(String replyId) {
        return activeReplyId == null || activeReplyId.equals(replyId);
    }

    /** 使当前回复失效并清除未播出任务，拒绝迟到的旧轮次事件。 */
    public synchronized void invalidateReply() {
        beginReply("cancelled:" + java.util.UUID.randomUUID());
    }

    /** 创建 {@code ConversationSessionAggregate}，初始化其依赖与状态。 */
    public ConversationSessionAggregate(String sessionId, String providerSessionId, String deviceId,
                                        String avatarId, String model, String voice, String scenarioId,
                                        SessionStatusEnum status) {
        this.sessionId = sessionId;
        this.providerSessionId = providerSessionId;
        this.deviceId = deviceId;
        this.avatarId = avatarId;
        this.model = model;
        this.voice = voice;
        this.scenarioId = scenarioId;
        this.status = status;
        this.createdAt = System.currentTimeMillis();
        this.lastEventAt = this.createdAt;
    }

    /**
     * 生成我方会话 id（s- 开头）。
     * 放这里是因为 id 的格式属于领域约定；随机源换成雪花/号段也只需要改这一处。
     */
    public static String newSessionId() {
        return "s-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    // ------------------------------------------------------------ 生命周期

    /** 判断会话是否仍在排队等待上游资源。 */
    public boolean isQueued() {
        return status == SessionStatusEnum.QUEUED;
    }

    /** 已经收尾的会话不能再用：关闭中、已关闭、出错都算。 */
    public boolean isFinished() {
        return status == SessionStatusEnum.CLOSING
                || status == SessionStatusEnum.CLOSED
                || status == SessionStatusEnum.ERROR;
    }

    /**
     * 能不能换 SDP。
     *
     * 两种情况都不行：
     * - 排队中：上游的 worker 还没起来，这时候发 offer 只会换来一个 404/409；
     * - 已经收尾（CLOSING/CLOSED/ERROR）：OpenTalking 在 WebRTC 断开时会自动关掉会话并释放
     *   GPU 槽位，那个 providerSessionId 已经作废 —— 再拿它去换 SDP 没有意义。
     *
     * 收尾态为什么要在本地提前拦掉，而不是交给适配器去发现：
     * 适配器在首次 404/409 之后会调 sessionGone() 探测，一旦认出会话已收尾就立刻抛
     * SESSION_NOT_FOUND，最多只吃一次上游 404；但如果这次状态探测本身失败、认不出已收尾，
     * 重试会一直持续到 readyTimeoutMs（默认 180s）才报错，用户看到的是「转三分钟然后报错」。
     */
    public boolean canExchangeOffer() {
        return !isQueued() && !isFinished();
    }

    /** 将会话标记为就绪。 */
    public void markReady() {
        this.status = SessionStatusEnum.READY;
        touch();
    }

    /** 将会话标记为活动状态。 */
    public void markActive() {
        this.status = SessionStatusEnum.ACTIVE;
        touch();
    }

    /** 将会话标记为正在关闭。 */
    public void markClosing() {
        this.status = SessionStatusEnum.CLOSING;
        touch();
    }

    /** 将会话标记为已关闭。 */
    public void markClosed() {
        this.status = SessionStatusEnum.CLOSED;
        touch();
    }

    /** 将会话标记为失败并保存错误信息。 */
    public void markError(String message) {
        this.status = SessionStatusEnum.ERROR;
        this.errorMessage = message;
        touch();
    }

    /** 状态由上游纠偏（poll status 或收到 session.ready）。 */
    public void markStatus(SessionStatusEnum newStatus) {
        this.status = newStatus;
        touch();
    }

    /**
     * 会话必须还开着才允许上行语音。
     * 这条规则归聚合管：调用方（领域服务）不该自己拼 CLOSED/CLOSING 的判断。
     */
    public void requireOpen() {
        if (isFinished()) {
            throw new AppException(ResponseCodeEnum.SESSION_NOT_OPEN, "会话已结束，无法上传语音");
        }
    }

    // ------------------------------------------------------------ 开场白

    /**
     * 标记「开场白已经说过」，返回这次调用是否真的完成了状态跃迁。
     *
     * 这是幂等的**业务不变式**：无论因为什么原因重复调用，只有第一次返回 true。
     *
     * 加 synchronized 不是防御性编程：用户连点两次「开始对话」，或客户端超时重试，
     * 两个请求会真的并发走到这里。重复开场的代价是数字人把第一句话说两遍 ——
     * 比多插一行数据严重得多，而且不可撤销。
     */
    public synchronized boolean markOpeningSpoken() {
        if (openingSpoken) {
            return false;
        }
        openingSpoken = true;
        touch();
        return true;
    }

    /** 判断本场开场白是否已经提交播报。 */
    public synchronized boolean isOpeningSpoken() {
        return openingSpoken;
    }
    /** 仅在下发请求失败时允许重试；重连和已受理的播报不重新介绍。 */
    public synchronized void openingSubmissionFailed() { openingSpoken=false; }

    /** 记一次「会话还活着」。心跳、任何上游事件都该调它。 */
    public void touch() {
        this.lastEventAt = System.currentTimeMillis();
    }

    /** 会话空闲了多久（毫秒），给以后的回收线程用。 */
    public long idleMillis() {
        return System.currentTimeMillis() - lastEventAt;
    }

    // ------------------------------------------------------------ 数字人回复

    /** 流式增量：累加到缓冲。 */
    public void appendAssistantDelta(String delta) {
        if (delta != null && !delta.isEmpty()) {
            assistantBuffer.append(delta);
        }
    }

    /**
     * 收尾并取出整段回复，同时清空缓冲。
     *
     * 契约：传进来的是这一轮的**整段回复**（适配器负责把各家 payload 规整成整段），
     * 所以以它为准，直接替换已累积的增量。
     *
     * 别再退回「猜」：真实流量里猜不出来。OpenTalking 的 subtitle.chunk 是剥掉
     * markdown 的 TTS 文本，而 speech.ended 是 LLM 原文（多出 ** 和空格），
     * 前缀比对失败后当成增量追加，字幕就会整段重复一遍。
     * 传空/不传时才退回已累积的内容。
     */
    public String finishAssistant(String finalText) {
        String buffer = assistantBuffer.toString();
        assistantBuffer.setLength(0);
        return finalText == null || finalText.isBlank() ? buffer : finalText;
    }

    // ------------------------------------------------------------ 用户这一轮

    /** 用户说完话了，记下时刻（后面算响应延迟就靠它）。 */
    public void noteUserSpeechEnded() {
        this.lastUserSpeechEndedAt = System.currentTimeMillis();
        touch();
    }

    /**
     * 这句用户台词是不是新的。
     * 上游可能把同一条识别结果推两遍（本地已经推过一次、上游又推一次），
     * 空文本和非新文本都要挡掉，否则回合会凭空多一条。
     */
    public boolean isNewUserText(String text) {
        return text != null && !text.isBlank() && !text.equals(lastUserText);
    }

    /** 保存最近一次有效用户原话。 */
    public void lastUserText(String text) {
        this.lastUserText = text;
    }

    /**
     * 从「用户说完」到「数字人回复定稿」隔了多久（毫秒）。
     * 还没开口过就返回 null —— 0 会被误读成「秒回」。
     */
    public Long responseLatencyMs() {
        return lastUserSpeechEndedAt > 0 ? System.currentTimeMillis() - lastUserSpeechEndedAt : null;
    }

    // ------------------------------------------------------------ 读写

    /** 读取业务会话标识。 */
    public String getSessionId() {
        return sessionId;
    }

    /** 读取上游会话标识。 */
    public String getProviderSessionId() {
        return providerSessionId;
    }

    /** 读取设备标识。 */
    public String getDeviceId() {
        return deviceId;
    }

    /** 读取数字人形象标识。 */
    public String getAvatarId() {
        return avatarId;
    }

    /** 读取模型标识。 */
    public String getModel() {
        return model;
    }

    /** 读取音色。 */
    public String getVoice() {
        return voice;
    }

    /** 读取场景标识。 */
    public String getScenarioId() {
        return scenarioId;
    }

    /** 读取创建时间。 */
    public long getCreatedAt() {
        return createdAt;
    }

    /** 读取会话状态。 */
    public SessionStatusEnum getStatus() {
        return status;
    }

    /** 读取最近活动时间。 */
    public long getLastEventAt() {
        return lastEventAt;
    }

    /** 读取错误说明。 */
    public String getErrorMessage() {
        return errorMessage;
    }

    /** 读取用户最近话音结束时间。 */
    public long getLastUserSpeechEndedAt() {
        return lastUserSpeechEndedAt;
    }

    /** 读取最近一次用户原话。 */
    public String getLastUserText() {
        return lastUserText;
    }
}

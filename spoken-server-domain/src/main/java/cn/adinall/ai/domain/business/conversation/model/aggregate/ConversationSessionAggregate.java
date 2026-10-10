package cn.adinall.ai.domain.business.conversation.model.aggregate;

import cn.adinall.ai.domain.business.conversation.model.valobj.SessionStatusEnum;
import cn.adinall.ai.types.enums.ResponseCodeEnum;
import cn.adinall.ai.types.exception.AppException;

import java.util.UUID;

/**
 * 会话聚合根 —— 这个系统里唯一真正有状态机的东西。
 *
 * 它把三件事收在一起，因为它们必须一起变：
 * 1. 会话的生命周期（QUEUED -> READY -> ... -> CLOSED / ERROR）；
 * 2. 数字人回复的流式缓冲（delta 累加到 done 收尾，中间可能被用户打断）；
 * 3. 「用户刚说完话」的时刻，用它算数字人的响应延迟。
 *
 * 刻意不放进来的东西：
 * - 事件总线（Sinks）：那是「怎么把事件送出去」，属于基础设施，不是领域状态；
 * - provider 会话句柄的细节：只知道要原样带回去，不需要理解它。
 *
 * 注意这里的字段可变、方法带副作用 —— 聚合根就是这样的，它不是 DTO。
 */
public class ConversationSessionAggregate {

    private final String sessionId;
    private final String providerSessionId;
    private final String deviceId;
    private final String avatarId;
    private final String model;
    private final String voice;
    /** 这场会话按哪个剧本走（见 ScenarioVO）。决定开场白说什么。 */
    private final String scenarioId;
    private final long createdAt;

    private SessionStatusEnum status;
    private long lastEventAt;
    private String errorMessage;

    /**
     * 开场白是否已经说过。
     *
     * 放内存不放库：会话本身就是进程内的（重启后 sessionId 一律查不到），
     * 把它落库对"防重放"没有任何帮助，反而多一份要同步的状态。
     * 和 lastUserText / assistantBuffer 同类：都是**这一场会话的运行态**。
     */
    private boolean openingSpoken;

    /** 用户最后一次说完话的时刻；用来算「数字人隔了多久才开口」。 */
    private long lastUserSpeechEndedAt;
    /** 最近一句用户台词。上游可能把同一条 stt.final 推两遍，靠它去重。 */
    private String lastUserText;
    /** 数字人这一轮的流式文本缓冲，done 时清空。 */
    private final StringBuilder assistantBuffer = new StringBuilder();

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
     *   GPU 槽位，那个 providerSessionId 从此永远不可用 —— 再发 offer 只会一直拿 404。
     *
     * 第二条是后补的：以前只挡 QUEUED，收尾的会话照样放行去换 SDP，结果适配器把上游的 404
     * 当成「worker 没就绪」，把 180s 的重试预算整个烧完，用户看到的是「转三分钟然后报错」。
     */
    public boolean canExchangeOffer() {
        return !isQueued() && !isFinished();
    }

    public void markReady() {
        this.status = SessionStatusEnum.READY;
        touch();
    }

    public void markActive() {
        this.status = SessionStatusEnum.ACTIVE;
        touch();
    }

    public void markClosing() {
        this.status = SessionStatusEnum.CLOSING;
        touch();
    }

    public void markClosed() {
        this.status = SessionStatusEnum.CLOSED;
        touch();
    }

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

    public synchronized boolean isOpeningSpoken() {
        return openingSpoken;
    }

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

    public String getSessionId() {
        return sessionId;
    }

    public String getProviderSessionId() {
        return providerSessionId;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public String getAvatarId() {
        return avatarId;
    }

    public String getModel() {
        return model;
    }

    public String getVoice() {
        return voice;
    }

    public String getScenarioId() {
        return scenarioId;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public SessionStatusEnum getStatus() {
        return status;
    }

    public long getLastEventAt() {
        return lastEventAt;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public long getLastUserSpeechEndedAt() {
        return lastUserSpeechEndedAt;
    }

    public String getLastUserText() {
        return lastUserText;
    }
}
package cn.adinall.ai.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * OpenTalking 接入配置（@ConfigurationProperties(prefix = "spoken-ai.provider.opentalking")）
 * <p>
 * 功能：把 yml 里「跟 OpenTalking 这个具体后端怎么说话」的参数绑成 Bean，供网关与适配器读取。
 * <pre>
 *   baseUrl              统一 API 地址，默认 http://127.0.0.1:8001
 *   defaultModel         建会话不指定模型时的默认值，默认 flashhead
 *   defaultAvatarId      建会话不指定形象时的默认值，默认 anchor
 *   readyTimeoutMs       等 worker 就绪的重试总时长，默认 180000
 *   offerRetryIntervalMs offer 撞 404/409 后的重试间隔，默认 1000
 *   dropStun             是否把 stun/stuns 从 ICE 列表里摘掉（TURN 保留），默认 false
 *   voiceTimeoutMs       语音上行等 STT 结果的最长时间，默认 60000
 *   connectTimeoutMs     HTTP 建连超时，默认 5000
 *   requestTimeoutMs     单次 HTTP 请求超时，默认 30000
 *   fallbackAvatarIds    /avatars 拉不到时的兜底形象，默认 [anchor]
 *   exposeModels         只暴露给 App 的模型，默认 [flashhead, flashtalk]，留空表示全部暴露
 * </pre>
 * 配置样例与消费方（谁读哪个字段）：
 * <pre>
 *   spoken-ai:
 *     provider:
 *       avatar: opentalking          # 选哪个 IAvatarDriverPort 实现
 *       opentalking:
 *         base-url: http://127.0.0.1:8001
 *         default-model: flashhead   # 演示用 1.3B，实时；flashtalk 是 14B，画质好但单块约 100s
 *         default-avatar-id: anchor
 *        |
 *        v
 *   AvatarDriverConfiguration: @EnableConfigurationProperties(OpenTalkingProperties.class)
 *        |
 *        +--> new OpenTalkingGateway(props)
 *        |        baseUrl / connectTimeoutMs / requestTimeoutMs / voiceTimeoutMs
 *        |
 *        +--> new OpenTalkingAvatarDriver(gateway, props)
 *                 defaultAvatarId / defaultModel / readyTimeoutMs / offerRetryIntervalMs
 *                 dropStun / fallbackAvatarIds / exposeModels
 * </pre>
 * 案例：
 * <pre>
 *   输入(yml): spoken-ai.provider.opentalking.default-model=flashtalk
 *             spoken-ai.provider.opentalking.drop-stun=true
 *   处理: Spring 松绑定调用 setDefaultModel("flashtalk") / setDropStun(true)
 *   输出: createSession 未指定模型时用 flashtalk（estimatedChunkMs=110000）；
 *         iceServers() 返回的列表里不再有 stun: / stuns: 条目
 *   缺省: 一项都不配时 baseUrl="http://127.0.0.1:8001"、defaultModel="flashhead"、
 *         defaultAvatarId="anchor"、readyTimeoutMs=180000、exposeModels=["flashhead","flashtalk"]
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>这些值全是「跟某个具体后端怎么说话」的细节（URL、超时、上游模型名），
 *       所以它属于基础设施层，不属于领域 —— 领域只通过 IAvatarDriverPort 提能力要求。</li>
 *   <li>默认值就是可直接跑起来的本机后端：不写任何配置也能连 127.0.0.1:8001，
 *       本地开发不必先准备配置文件。</li>
 *   <li>dropStun 默认 false 是保守选择：局域网直连部署置 true 能让非 trickle 的手机
 *       少等一轮 STUN 候选收集（实测约 7 秒），但需要穿透 NAT / 走 TURN 的部署必须保持 false。</li>
 *   <li>exposeModels 是「给 App 看什么」的白名单，不是「上游有什么」：
 *       留空（null 或空列表）才表示全暴露，写错模型 id 的表现是 App 模型列表里少一项。</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "spoken-ai.provider.opentalking")
public class OpenTalkingProperties {

    /** OpenTalking 统一 API 地址，例如 http://127.0.0.1:8001 */
    private String baseUrl = "http://127.0.0.1:8001";

    /** 建会话时不指定模型时用的默认模型。 */
    private String defaultModel = "flashhead";

    /** 建会话时不指定形象时用的默认形象。 */
    private String defaultAvatarId = "anchor";

    /** 建会话后等待模型 worker 就绪的最长时间（毫秒）。OpenTalking 单槽排队时可能较久。 */
    private long readyTimeoutMs = 180_000L;

    /**
     * WebRTC offer 的重试间隔（毫秒）。
     *
     * 会话刚建好、worker 还没挂到 GPU 槽位上时，OpenTalking 的
     * /sessions/{id}/webrtc/offer 会返回 404 "session not loaded"。
     * 这是单槽调度下的正常排队现象，不是错误 —— 等一会重试就好，
     * 重试总时长沿用 readyTimeoutMs。
     */
    private long offerRetryIntervalMs = 1_000L;

    /**
     * 是否把 stun / stuns 从返回给 App 的 ICE 服务器列表里摘掉（TURN 原样保留）。
     *
     * 局域网直连部署（手机和后端在可达网段内）时，真正建连的是 host↔host 候选，
     * STUN 只贡献一个用不上的 srflx 候选。而 App 是非 trickle 的：必须等
     * iceGatheringState 变成 COMPLETE 才发 offer，手机有 Wi-Fi + 蜂窝两张网卡时
     * 实测要为等 STUN 多花约 7 秒。置 true 后手机只收集 host 候选，毫秒级完成。
     *
     * 需要穿透 NAT / 走 TURN 的部署请保持 false。
     */
    private boolean dropStun = false;

    /**
     * 语音上行后等 STT 结果的最长时间（毫秒）。
     *
     * 上游是「把整段 PCM 收完再跑识别」，所以这段是纯等待，不是流式。
     * 实测 3.9 秒音频识别只要 0.31 秒，60 秒足够覆盖长句 + 上游偶发排队。
     */
    private long voiceTimeoutMs = 60_000L;

    /** 建立连接的超时（毫秒），用于 HTTP 建连。 */
    private int connectTimeoutMs = 5_000;

    /** 单次 HTTP 请求的超时（毫秒）。 */
    private int requestTimeoutMs = 30_000;

    /** /avatars 拉不到时的兜底形象列表（避免后端重启后前端空白）。 */
    private List<String> fallbackAvatarIds = new ArrayList<>(List.of("anchor"));

    /** 只暴露这些模型给 App；留空表示全部暴露。 */
    private List<String> exposeModels = new ArrayList<>(List.of("flashhead", "flashtalk"));

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getDefaultModel() {
        return defaultModel;
    }

    public void setDefaultModel(String defaultModel) {
        this.defaultModel = defaultModel;
    }

    public String getDefaultAvatarId() {
        return defaultAvatarId;
    }

    public void setDefaultAvatarId(String defaultAvatarId) {
        this.defaultAvatarId = defaultAvatarId;
    }

    public long getReadyTimeoutMs() {
        return readyTimeoutMs;
    }

    public void setReadyTimeoutMs(long readyTimeoutMs) {
        this.readyTimeoutMs = readyTimeoutMs;
    }

    public long getOfferRetryIntervalMs() {
        return offerRetryIntervalMs;
    }

    public void setOfferRetryIntervalMs(long offerRetryIntervalMs) {
        this.offerRetryIntervalMs = offerRetryIntervalMs;
    }

    public boolean isDropStun() {
        return dropStun;
    }

    public void setDropStun(boolean dropStun) {
        this.dropStun = dropStun;
    }

    public long getVoiceTimeoutMs() {
        return voiceTimeoutMs;
    }

    public void setVoiceTimeoutMs(long voiceTimeoutMs) {
        this.voiceTimeoutMs = voiceTimeoutMs;
    }

    public int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(int connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public int getRequestTimeoutMs() {
        return requestTimeoutMs;
    }

    public void setRequestTimeoutMs(int requestTimeoutMs) {
        this.requestTimeoutMs = requestTimeoutMs;
    }

    public List<String> getFallbackAvatarIds() {
        return fallbackAvatarIds;
    }

    public void setFallbackAvatarIds(List<String> fallbackAvatarIds) {
        this.fallbackAvatarIds = fallbackAvatarIds;
    }

    public List<String> getExposeModels() {
        return exposeModels;
    }

    public void setExposeModels(List<String> exposeModels) {
        this.exposeModels = exposeModels;
    }
}
package cn.adinall.ai.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * OpenPronounce 接入配置（@ConfigurationProperties(prefix = "spoken-ai.provider.openpronounce")）
 * <p>
 * 功能：把 yml 里「跟这台 OpenPronounce 服务怎么说话」的参数绑成 Bean，供网关与适配器读取。
 * <pre>
 *   baseUrl            服务地址，默认 http://127.0.0.1:8070（本机 docker 映射端口）
 *   defaultLang        调用方没给 lang 时用的语言码，默认 en
 *   connectTimeoutMs   HTTP 建连超时，默认 5000
 *   requestTimeoutMs   普通请求（音素化 / 识别 / 合成 / 语言表）超时，默认 60000
 *   assessTimeoutMs    评测请求超时，默认 180000（明显更长，见下）
 *   maxAudioBytes      单次上传音频的字节上限，默认 15MB
 *   exposeLanguages    只暴露给 App 的语言白名单；留空表示全部暴露
 * </pre>
 * 配置样例与消费方（谁读哪个字段）：
 * <pre>
 *   spoken-ai:
 *     provider:
 *       pronounce: openpronounce          # 选哪个 IPronunciationPort 实现
 *       openpronounce:
 *         base-url: http://172.18.0.102:8070
 *         default-lang: en
 *         assess-timeout-ms: 180000
 *        |
 *        v
 *   PronunciationConfiguration: @EnableConfigurationProperties(OpenPronounceProperties.class)
 *        |
 *        +--> new OpenPronounceGateway(props)
 *        |        baseUrl / connectTimeoutMs / requestTimeoutMs / assessTimeoutMs / maxAudioBytes
 *        |
 *        +--> new OpenPronouncePronunciationAdapter(gateway, props)
 *                 defaultLang / exposeLanguages
 * </pre>
 * 案例：
 * <pre>
 *   输入(yml): spoken-ai.provider.openpronounce.base-url=http://172.18.0.102:8070
 *   处理: Spring 松绑定调用 setBaseUrl("http://172.18.0.102:8070")
 *   输出: 网关把所有请求打到该地址；容器内实际是 host 8070 -> container 8000
 *   缺省: 一项都不配时 baseUrl="http://127.0.0.1:8070"、defaultLang="en"、
 *         requestTimeoutMs=60000、assessTimeoutMs=180000、maxAudioBytes=15MB
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>这些值全是「跟某个具体评测服务怎么说话」的细节（URL、超时、大小上限），
 *       所以属于基础设施层，不属于领域 —— 领域只通过 IPronunciationPort 提能力要求。</li>
 *   <li>assessTimeoutMs 单独一项而不是复用 requestTimeoutMs：一次评测要跑两个 Wav2Vec2
 *       模型（词识别 + 音素识别）加一次 DTW 对齐，本机 GPU 上实测约 0.19 秒，
 *       但同样的容器在 OPENPRONOUNCE_DEVICE=cpu 下会慢一到两个数量级。
 *       把评测和「查一下支持哪些语言」绑在同一个超时上，要么误杀 CPU 部署，要么让轻请求白等两分钟。</li>
 *   <li>maxAudioBytes 在**本地**先挡而不是等上游回 413：上游收满整个 body 才判断大小，
 *       本地先挡能省掉一次 15MB 的上传（手机流量 + 上游内存峰值）。</li>
 *   <li>exposeLanguages 是「给 App 看什么」的白名单，不是「上游有什么」：
 *       上游的 fr/es/de/it/pt/nl 是实验性的（只有英语对着人工评分校准过），
 *       产品上先只放英语时，把这一项配成 [en] 即可，不必动代码。</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "spoken-ai.provider.openpronounce")
public class OpenPronounceProperties {

    /** OpenPronounce 服务地址。本机 docker 部署时 8070 映射到容器内 8000。 */
    private String baseUrl = "http://127.0.0.1:8070";

    /** 调用方没给 lang 时用的语言码。上游默认语言是 en，这里保持一致。 */
    private String defaultLang = "en";

    /** HTTP 建连超时（毫秒）。 */
    private int connectTimeoutMs = 5_000;

    /** 普通请求超时（毫秒）：音素化、识别、合成、语言表。 */
    private int requestTimeoutMs = 60_000;

    /**
     * 评测请求超时（毫秒）。
     *
     * 评测是重活：两个 Wav2Vec2 模型 + DTW，GPU 上约 0.2 秒、CPU 上可能几十秒。
     * 默认给到 180 秒，既不误杀 CPU 部署，也不至于让线程被一个死连接永久占住。
     */
    private int assessTimeoutMs = 180_000;

    /** 单次上传音频的字节上限。默认 15MB，与 OpenTalking 侧的音频上限保持一致。 */
    private int maxAudioBytes = 15 * 1024 * 1024;

    /** 只暴露这些语言给 App；留空表示全部暴露。 */
    private List<String> exposeLanguages = new ArrayList<>();

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getDefaultLang() {
        return defaultLang;
    }

    public void setDefaultLang(String defaultLang) {
        this.defaultLang = defaultLang;
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

    public int getAssessTimeoutMs() {
        return assessTimeoutMs;
    }

    public void setAssessTimeoutMs(int assessTimeoutMs) {
        this.assessTimeoutMs = assessTimeoutMs;
    }

    public int getMaxAudioBytes() {
        return maxAudioBytes;
    }

    public void setMaxAudioBytes(int maxAudioBytes) {
        this.maxAudioBytes = maxAudioBytes;
    }

    public List<String> getExposeLanguages() {
        return exposeLanguages;
    }

    public void setExposeLanguages(List<String> exposeLanguages) {
        this.exposeLanguages = exposeLanguages;
    }
}

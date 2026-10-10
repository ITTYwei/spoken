package cn.adinall.ai.trigger.http;

import cn.adinall.ai.api.dto.LanguagesResponseDTO;
import cn.adinall.ai.api.dto.PhonemesResponseDTO;
import cn.adinall.ai.api.dto.TranscriptResponseDTO;
import cn.adinall.ai.cases.pronunciation.IPronunciationCase;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

/**
 * 发音评测工具箱 HTTP 入口（不依赖会话，路由前缀 /api/v1/pronunciation）
 * <p>
 * 功能：把 OpenPronounce 的四个附属能力（识别 / 音素化 / 参考音合成 / 语言表）与存活探测透出去，
 * 供 App 在进房之前就能用（例如先展示标准音标、播放标准发音）。
 * Controller 只做形状转换，不写业务逻辑。
 * <pre>
 *   GET  /api/v1/pronunciation/languages      支持的语言与默认语言
 *   GET  /api/v1/pronunciation/health         评测服务存活探测（永不 5xx）
 *   POST /api/v1/pronunciation/speech2text    纯识别（multipart: file + lang）
 *   POST /api/v1/pronunciation/phonemes       文本音素化（form: text + lang）
 *   POST /api/v1/pronunciation/tts            合成参考发音（form: text + lang）-> audio/wav
 * </pre>
 * 运行过程（App 跟读前的一次准备）：
 * <pre>
 *   App 拿到题目 "Hello, how are you?"
 *        |
 *        | (1) POST /api/v1/pronunciation/phonemes   text=Hello, how are you?&lang=en
 *        |        v
 *        |     200 {"words":["Hello","how","are","you"],
 *        |          "phonemes":["həloʊ","haʊ","ɑːɹ","juː"]}
 *        |     （展示音标，让用户知道这句怎么念）
 *        |
 *        | (2) POST /api/v1/pronunciation/tts        text=Hello, how are you?&lang=en
 *        |        v
 *        |     200 二进制 audio/wav（16kHz 单声道，约 37KB）
 *        |     （播放标准发音）
 *        |
 *        | (3) 用户跟读 -> POST /api/v1/sessions/{id}/pronunciation 走评测
 *        v
 *   评测结果
 * </pre>
 * 案例：
 * <pre>
 *   输入: GET /api/v1/pronunciation/languages
 *   输出: 200 {"defaultLang":"en","languages":[{"code":"en","name":"English"}, ...]}
 *
 *   输入: POST /api/v1/pronunciation/speech2text   multipart file=clip.wav, lang=en
 *   输出: 200 {"transcript":"HELLO HOW ARE YOU"}
 *
 *   输入: GET /api/v1/pronunciation/health
 *   输出: 200 {"status":"ok"}   —— 上游挂了也是 200 {"status":"down"}，不让探针拿到 5xx
 *
 *   输入: 上游不可达时 POST /phonemes
 *   输出: 503 {"code":"provider_unavailable","message":"OpenPronounce 请求失败: <uri>","ts":...}
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>这些接口不带 sessionId：它们不落库、不依赖 WebRTC 连接，用户还在选题目时就要用得上。
 *       强行要求会话会让「先看音标再决定读不读」这个正常流程没法走。</li>
 *   <li>/health 永远返回 200（body 里带 status）：探针的语义是「HTTP 层通不通」，
 *       上游不健康时回 503 会让探针与「后端自己挂了」混在一起。真正的业务失败
 *       由各接口自己的 502/503 表达。</li>
 *   <li>/tts 直接回 audio/wav 二进制而不是 JSON + base64：多一层编码白胖 33%，
 *       而且 App 要的就是能直接喂给播放器的字节。</li>
 *   <li>/phonemes 与 /tts 用表单字段（application/x-www-form-urlencoded）而不是 JSON body：
 *       与上游 OpenPronounce 的契约保持一致，两边抓包形状一样，排查少一次心算。</li>
 *   <li>不在这里做语言白名单过滤：过滤属于「给 App 看什么」的适配器决策
 *       （spoken-ai.provider.openpronounce.expose-languages），Controller 只管转发。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/pronunciation")
public class PronunciationToolController {

    private final IPronunciationCase pronunciationCase;

    public PronunciationToolController(IPronunciationCase pronunciationCase) {
        this.pronunciationCase = pronunciationCase;
    }

    /** 支持的语言与默认语言。App 可以据此决定要不要显示语言选择器。 */
    @GetMapping("/languages")
    public LanguagesResponseDTO languages() {
        return pronunciationCase.languages();
    }

    /**
     * 评测服务存活探测。
     * 永远 200：上游不可达时 body 里是 {"status":"down"}，让探针区分「HTTP 通不通」与「上游好不好」。
     */
    @GetMapping("/health")
    public Map<String, Object> health() {
        boolean healthy = pronunciationCase.healthy();
        return Map.of("status", healthy ? "ok" : "down", "provider", "openpronounce");
    }

    /** 纯语音识别：音频 -> 整句文本（不做评分）。 */
    @PostMapping("/speech2text")
    public TranscriptResponseDTO speech2text(@RequestParam("file") MultipartFile file,
                                             @RequestParam(value = "lang", required = false) String lang)
            throws IOException {
        return pronunciationCase.transcribe(file.getOriginalFilename(), file.getContentType(),
                lang, file.getBytes());
    }

    /** 文本音素化：逐词 IPA，供跟读前展示标准音标。 */
    @PostMapping("/phonemes")
    public PhonemesResponseDTO phonemes(@RequestParam("text") String text,
                                        @RequestParam(value = "lang", required = false) String lang) {
        return pronunciationCase.phonemes(text, lang);
    }

    /**
     * 合成参考发音，返回 16kHz 单声道 wav。
     * 用 ResponseEntity 而不是直接返回 byte[]：必须显式带 audio/wav 的 Content-Type，
     * 否则 Spring 会用 application/octet-stream，App 侧有的播放器不肯认。
     */
    @PostMapping(value = "/tts", produces = "audio/wav")
    public ResponseEntity<byte[]> tts(@RequestParam("text") String text,
                                      @RequestParam(value = "lang", required = false) String lang) {
        byte[] wav = pronunciationCase.synthesize(text, lang);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "audio/wav")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentLength(wav.length)
                .body(wav);
    }
}

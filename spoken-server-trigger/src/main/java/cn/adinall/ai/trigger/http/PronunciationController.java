package cn.adinall.ai.trigger.http;

import cn.adinall.ai.api.dto.AssessmentRecordResponseDTO;
import cn.adinall.ai.api.dto.PronunciationAssessmentResponseDTO;
import cn.adinall.ai.cases.pronunciation.IPronunciationCase;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/**
 * 跟读评测 HTTP 入口（会话作用域，路由前缀 /api/v1/sessions/{sessionId}/pronunciation）
 * <p>
 * 功能：App 上传一段跟读录音 + 题面，拿回分数、错词与韵律；也可以拉本场会话的历史评测记录。
 * Controller 里没有一行业务逻辑：它只做「multipart -&gt; byte[]」这一层形状转换。
 * <pre>
 *   POST /api/v1/sessions/{sessionId}/pronunciation   上传录音评测（multipart）
 *   GET  /api/v1/sessions/{sessionId}/pronunciation   本场评测记录（最近的在前）
 * </pre>
 * 表单字段（POST）：
 * <pre>
 *   file          录音文件（必填）；格式随 ffmpeg，wav / mp3 / m4a / ogg / webm 都行
 *   expectedText  这句话应该是什么（必填）—— 评测就是拿它和录音比
 *   lang          语言码（选填，默认 en）；见 GET /api/v1/pronunciation/languages
 *   turnId        关联的对话回合 id（选填）；带上它，记录就能挂到这一轮问答上
 * </pre>
 * 运行过程（一次跟读评测）：
 * <pre>
 *   App（按住说话 / 跟读录音）
 *        |
 *        | POST /api/v1/sessions/s-001/pronunciation
 *        |      multipart: file=follow.wav, expectedText="Hello, how are you?", lang=en
 *        v
 *   PronunciationController.assess(...)
 *        |
 *        | (1) file.getBytes() 把上传流读成字节（读失败 -> 500，见注释）
 *        | (2) conversationCase 无关；直接交给 IPronunciationCase
 *        v
 *   PronunciationCaseImpl.assess(sessionId, expectedText, lang, turnId, filename, contentType, audio)
 *        |
 *        | 会话校验 -> 上游评测（~0.2s）-> 落库 -> 装配
 *        v
 *   200 {"assessmentId":1,"score":86.67,"errorCount":0,"errors":[],...}
 * </pre>
 * 案例：
 * <pre>
 *   输入: POST /api/v1/sessions/s-001/pronunciation
 *         multipart: file=follow.wav（37940B）, expectedText="Hello, how are you?", lang=en
 *   处理: 读字节 -> 用例 -> 上游 192ms 返回 86.67 分 -> 落库 id=1
 *   输出: 200 {"assessmentId":1,"sessionId":"s-001","expectedText":"Hello, how are you?",
 *          "lang":"en","latencyMs":192,"score":86.67,"transcribe":"HELLO HOW ARE YOU",
 *          "wordErrorRate":0.0,"phonemeErrorRate":0.3333,"acousticDistance":0.0,
 *          "errorCount":0,"errors":[],"prosody":{"f0":[...],"energy":[...]}}
 *         GET 同路径: [ {"assessmentId":1,"score":86.67,"errorCount":0,...} ]
 *         会话不存在: 404 {"code":"session_not_found","message":"会话不存在或已结束: s-404",...}
 *         音频为空:   400 {"code":"invalid_request","message":"跟读音频为空，请重新录制",...}
 *         音频过大:   413 {"code":"audio_too_large","message":"音频 ... 字节，超过上限 ...",...}
 *         上游不可达: 503 {"code":"provider_unavailable","message":"OpenPronounce 请求失败: ...",...}
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>用 multipart 而不是 octet-stream：跟读评测天然要带题面（expectedText）与语言，
 *       而且 OpenPronounce 本身收的就是 multipart —— 用表单字段能少一层「音频旁边挂几个 header」的约定。
 *       现有的 /voice 保持 octet-stream 不动（那是 WebRTC 上行链路的历史契约，App 已按它实现）。</li>
 *   <li>turnId 用 @RequestParam(required=false) Long 而不是 String 再自己 parse：
 *       让 Spring 的类型转换去挡「turnId=abc」，转换失败会走 400，不必手写 try/catch。</li>
 *   <li>读 MultipartFile 的 IOException 直接向上抛给 GlobalExceptionHandler：
 *       上传被中断（用户取消、网络断）不是业务错误，落 500 且不打业务日志即可
 *       （GlobalExceptionHandler 对 IOException 只记 debug）。</li>
 *   <li>不设 consumes 限制：multipart 的 Content-Type 带 boundary，写死反而容易漏配；
 *       缺 file / expectedText 字段时由 @RequestParam 抛 MissingServletRequestPartException，
 *       GlobalExceptionHandler 把它映射成 400 invalid_request（不是 500）。</li>
 *   <li>返回体就是 PronunciationAssessmentResponseDTO，不包 Response 外壳 ——
 *       与 SessionController 一组接口保持同一种「裸 JSON」约定，App 只学一套解包方式。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/sessions/{sessionId}/pronunciation")
public class PronunciationController {

    private final IPronunciationCase pronunciationCase;

    public PronunciationController(IPronunciationCase pronunciationCase) {
        this.pronunciationCase = pronunciationCase;
    }

    /**
     * 上传一段跟读录音并评测。
     *
     * 音频走向是「App 上传文件」而不是「取服务端 WebRTC 上行缓冲」：
     * 后者那份音频过了 AEC3（回声已消）但 Java 侧拿不到字节（只有 OpenTalking 认得它），
     * 所以当前先用上传路径；将来打通上行音频出口时，只换 audio 的来源，本方法签名与用例都不用改。
     */
    @PostMapping
    public PronunciationAssessmentResponseDTO assess(@PathVariable String sessionId,
                                                     @RequestParam("file") MultipartFile file,
                                                     @RequestParam("expectedText") String expectedText,
                                                     @RequestParam(value = "lang", required = false) String lang,
                                                     @RequestParam(value = "turnId", required = false) Long turnId)
            throws IOException {
        byte[] audio = file.getBytes();
        return pronunciationCase.assess(sessionId, expectedText, lang, turnId,
                file.getOriginalFilename(), file.getContentType(), audio);
    }

    /** 本场会话的评测记录，最近的在前。 */
    @GetMapping
    public List<AssessmentRecordResponseDTO> list(@PathVariable String sessionId) {
        return pronunciationCase.listBySessionId(sessionId);
    }
}

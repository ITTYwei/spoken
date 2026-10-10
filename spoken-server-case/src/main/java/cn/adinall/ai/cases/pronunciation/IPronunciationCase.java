package cn.adinall.ai.cases.pronunciation;

import cn.adinall.ai.api.dto.AssessmentRecordResponseDTO;
import cn.adinall.ai.api.dto.LanguagesResponseDTO;
import cn.adinall.ai.api.dto.PhonemesResponseDTO;
import cn.adinall.ai.api.dto.PronunciationAssessmentResponseDTO;
import cn.adinall.ai.api.dto.TranscriptResponseDTO;

import java.util.List;

/**
 * 发音评测用例（App 的一次请求 = 这里的一个方法）
 * <p>
 * 功能：把 HTTP 入参翻成领域调用，再把领域结果翻回 api 模块的 DTO；
 * 「会话在不在」「音频能不能收」这类编排判断在这一层，评分规则本身在领域里。
 * <pre>
 *   assess(sessionId, ...)          跟读评测：校验会话 -> 领域评测+落库 -> 拼完整结果
 *   listBySessionId(sessionId)      取某场会话的评测记录（最近的在前）
 *   transcribe(audio, ...)          纯识别（工具）
 *   phonemes(text, lang)            文本音素化（工具）
 *   synthesize(text, lang)          合成参考发音，返回 16k wav 字节（工具）
 *   languages()                     语言注册表（工具）
 *   healthy()                       评测服务存活探测
 * </pre>
 * 体系架构（它两侧的边界）：
 * <pre>
 *   PronunciationController / PronunciationToolController   只做 HTTP 形状翻译
 *        |
 *        | 对外只认 api 模块 DTO：PronunciationAssessmentResponseDTO /
 *        | AssessmentRecordResponseDTO / PhonemesResponseDTO / LanguagesResponseDTO /
 *        | TranscriptResponseDTO
 *        v
 *   IPronunciationCase（本接口）
 *        |
 *        | 对内只认领域类型：AssessPronunciationCommandEntity / PronunciationAssessmentAggregate
 *        v
 *   IConversationService（校验会话存在）  +  IPronunciationService（评测与落库）
 *        |
 *        +--> IPronunciationPort（上游，只被领域服务调用）
 * </pre>
 * 案例：
 * <pre>
 *   输入: assess("s-001", "Hello, how are you?", "en", null, "follow.wav", "audio/wav", wavBytes)
 *   处理: requireSession 通过 -> 领域评测（192ms）-> 落库 id=1 -> 拼 DTO
 *   输出: PronunciationAssessmentResponseDTO{assessmentId=1, score=86.67, errors=[], ...}
 *   会话不存在: 抛 AppException{session_not_found} -> 404
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>评测入口有会话作用域（assess / listBySessionId），工具入口没有（transcribe / phonemes /
 *       synthesize / languages）：前者产出要落库、要挂到某场会话上；后者是「顺手用一下上游」，
 *       强行要求会话反而让 App 没法在进房前就给用户看标准音标。</li>
 *   <li>会话存在性校验放在用例层而不是领域服务：查会话需要 IConversationService，
 *       领域服务保持「只依赖自己需要的那几个端口」，不为了一个校验去引入会话仓储。</li>
 *   <li>Controller 不直接调 IPronunciationPort：工具接口虽然只是透传，也走一遍用例层 ——
 *       这样「加缓存 / 加限流 / 加埋点」有唯一的落点，不用再回头改 Controller。</li>
 * </ul>
 */
public interface IPronunciationCase {

    /**
     * 跟读评测：对一段录音按题面打分并落库。
     *
     * @param sessionId    所属会话 id（必须真实存在，否则 404）
     * @param expectedText 题面（必须非空，否则 400）
     * @param lang         语言码，可为空（用后端默认）
     * @param turnId       所属回合 id，可为空
     * @param filename     原始文件名，用于告诉上游容器格式
     * @param contentType  上传的 MIME，仅留档
     * @param audio        录音字节（必须非空，否则 400）
     */
    PronunciationAssessmentResponseDTO assess(String sessionId, String expectedText, String lang, Long turnId,
                                              String filename, String contentType, byte[] audio);

    /** 取某场会话的全部评测记录，按创建时间倒序。 */
    List<AssessmentRecordResponseDTO> listBySessionId(String sessionId);

    /** 纯语音识别：音频 -> 整句文本。 */
    TranscriptResponseDTO transcribe(String filename, String contentType, String lang, byte[] audio);

    /** 文本音素化：逐词 IPA。 */
    PhonemesResponseDTO phonemes(String text, String lang);

    /** 合成参考发音：16kHz 单声道 wav 字节。 */
    byte[] synthesize(String text, String lang);

    /** 语言注册表：支持哪些语言、默认哪个。 */
    LanguagesResponseDTO languages();

    /** 评测服务存活探测：不抛异常，连不上就是 false。 */
    boolean healthy();
}

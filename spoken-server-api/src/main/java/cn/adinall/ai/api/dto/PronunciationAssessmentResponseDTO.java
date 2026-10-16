package cn.adinall.ai.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 语音主链路中一次跟读评测的完整结果（/voice 响应中的 assessment 字段）
 * <p>
 * 功能：把「这次跟读得了多少分、哪里念错了、音调什么样」一次给全，App 拿到就能直接渲染结果页。
 * 前半段（assessmentId..createdAt）是我方元数据，后半段是评测结果本身。
 * record 的组件名就是 JSON 字段名。
 * <pre>
 *   assessmentId          评测记录 id（库里的自增主键）；用于关联持久评测记录
 *   sessionId             所属会话
 *   turnId                所属回合；可为 null（这次跟读没挂回合）
 *   expectedText          本次跟读的题面
 *   lang                  实际使用的语言码（已兜底）
 *   latencyMs             后端调一次评测的耗时（毫秒），用于展示「评测用了多久」与容量评估
 *   createdAt             评测完成时间（毫秒时间戳）
 *   score                 总分 0~100
 *   acousticDistance      声学 DTW 均距：学习者嵌入与合成母语参考的差距，越小越像
 *   distance              上游原始距离（整数），调参用
 *   transcribe            词识别器听到的整句（如 "HELLO HOW ARE YOU"）
 *   language              上游回传的语言码
 *   feedback              上游生成的一句人话反馈（含 emoji 的整段文本）
 *   wordErrorRate         词错误率 0~1
 *   phonemeErrorRate      音素错误率 0~1
 *   wordDistance          词级编辑距离（原始计数）
 *   phonemeDistance       音素级编辑距离（原始计数）
 *   errorCount            判为错的词数；等价 errors.size()
 *   errors                错词明细（含逐音素）
 *   wordsWithErrors       出错词的原文列表（快速高亮用）
 *   heardPhones           音频里实际识别到的全部音素（扁平序列）
 *   heardPhonesConfidence 与 heardPhones 一一对应的置信度
 *   expectedPhones        题面逐词的音素（外层按词、内层按音）
 *   expectedPhonemes      题面逐词的 IPA 串
 *   transcribedPhonemes   实际听到的逐词 IPA 串
 *   expectedVector        期望音素的数值轨迹（已对齐，可直接画图）
 *   transcribedVector     实际音素的数值轨迹（与上面同长）
 *   prosody               音高与能量曲线
 * </pre>
 * 运行过程：
 * <pre>
 *   POST /api/v1/sessions/s-001/voice  (application/octet-stream audio)
 *        |
 *        v
 *   SessionController.voice(...) -> ConversationCaseImpl.voice(...)
 *        |
 *        | (1) requireSession + trusted teaching context
 *        | (2) pronunciationService.assess(...) -> persisted assessment
 *        | (3) PronunciationAssembler.toResponse(assessment)
 *        v
 *   {"assessmentId":1,"sessionId":"s-001","turnId":null,"expectedText":"Hello, how are you?",
 *    "lang":"en","latencyMs":192,"score":86.67,"transcribe":"HELLO HOW ARE YOU",
 *    "wordErrorRate":0.0,"phonemeErrorRate":0.3333,"acousticDistance":0.0,
 *    "errorCount":0,"errors":[],"prosody":{"f0":[...],"energy":[...]}, ...}
 * </pre>
 * 案例：
 * <pre>
 *   输入: 用户跟读 "Hello, how are you?"，读得不错
 *   处理: 上游 86.67 分、没错词 -> 落库 id=1
 *   输出: {"assessmentId":1,...,"score":86.67,"errorCount":0,"errors":[],"latencyMs":192}
 *   念错整句时: {"score":0.0,"errorCount":5,"errors":[...5 条，含逐音素...],
 *                "feedback":"🔊 Feedback on your pronunciation:\n❌ You need to better pronounce ..."}
 *   会话不存在: 404 {"code":"session_not_found","message":"会话不存在或已结束: s-001","ts":...}
 *   音频为空:   400 {"code":"invalid_request","message":"跟读音频为空，请重新录制","ts":...}
 *   音频过大:   413 {"code":"audio_too_large","message":"音频 ... 字节，超过上限 ...","ts":...}
 *   上游不可达: 503 {"code":"provider_unavailable","message":"OpenPronounce 请求失败: <uri>","ts":...}
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>字段名尽量与上游同名（score / transcribe / errors / prosody），只是把 snake_case
 *       换成 camelCase：这样 App 开发者对着 OpenPronounce 文档也能看懂，排查时两边能逐字段对照。</li>
 *   <li>标注 @JsonInclude(NON_NULL)：上游可能不给韵律或某个数值，null 的键直接不下发，
 *       App 不必对每个字段都判一次 null（与 SessionResponseDTO / TurnResponseDTO 同一约定）。</li>
 *   <li>既给 errors 也给 errorCount / wordsWithErrors：前者用于展开细节，后两者用于
 *       「一句话总结」（如「3 个词没念好」）与高亮，避免客户端为了显示一个数字去遍历数组。</li>
 *   <li>分数字段用 Double 而不是 double：上游确实可能不给分（那时后端抛 502 而不是返回 0），
 *       用基本类型会把「没有」渲染成「0 分」，而 0 分在跟读产品里是个很重的结论。</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PronunciationAssessmentResponseDTO(
        Long assessmentId,
        String sessionId,
        Long turnId,
        String expectedText,
        String lang,
        Long latencyMs,
        Long createdAt,
        Double score,
        Double acousticDistance,
        Double distance,
        String transcribe,
        String language,
        String feedback,
        Double wordErrorRate,
        Double phonemeErrorRate,
        Double wordDistance,
        Double phonemeDistance,
        Integer errorCount,
        List<PronunciationErrorResponseDTO> errors,
        List<String> wordsWithErrors,
        List<String> heardPhones,
        List<Double> heardPhonesConfidence,
        List<List<String>> expectedPhones,
        List<String> expectedPhonemes,
        List<String> transcribedPhonemes,
        List<Double> expectedVector,
        List<Double> transcribedVector,
        PronunciationProsodyResponseDTO prosody
) {
}

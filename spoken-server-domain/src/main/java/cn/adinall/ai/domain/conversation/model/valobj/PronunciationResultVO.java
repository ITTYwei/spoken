package cn.adinall.ai.domain.conversation.model.valobj;

import java.util.List;

/**
 * 一次发音评测的完整结果（OpenPronounce /pronunciation 的领域形状）
 * <p>
 * 功能：把「读了多少分、识别成什么、哪些词错了、音高能量什么样」这一整份答案收成一个不可变对象，
 * 它是「跟读评测」这个能力的领域出口，也是落库与对外响应的唯一数据源。
 * <pre>
 *   score                 总分 0~100（= 0.3×声学 + 0.4×(1-音素错误率) + 0.3×(1-词错误率)）
 *   acousticDistance      学习者嵌入与合成母语参考的 DTW 均距，越小越像；6 分满分、15 分 0 分
 *   distance              上游的原始距离（整数），供调参用；一般直接看 acousticDistance
 *   transcribe            词识别器听到的整句（如 "HELLO HOW ARE YOU"）
 *   language              实际使用的语言码（en/fr/es/...）
 *   feedback              上游生成的一句人话反馈（带 emoji 的整段文本）
 *   wordErrorRate         词错误率 0~1（改了几个词 / 期望几个词）
 *   phonemeErrorRate      音素错误率 0~1（改了几个音 / 期望几个音）
 *   wordDistance          词级编辑距离（原始计数）
 *   phonemeDistance       音素级编辑距离（原始计数）
 *   errors                判为念错/漏读的词，逐条含逐音素明细
 *   wordsWithErrors       出错词的原文列表（给 App 快速高亮用，等价 errors[].word）
 *   heardPhones           音频里实际识别到的全部音素（IPA，扁平序列）
 *   heardPhonesConfidence 与 heardPhones 一一对应的置信度
 *   expectedPhones        期望文本逐词的音素（外层按词、内层按音）
 *   expectedPhonemes      期望文本逐词的 IPA 串（如 ["həloʊ","haʊ","ɑːɹ","juː"]）
 *   transcribedPhonemes   实际听到的逐词 IPA 串
 *   expectedVector        期望音素的数值轨迹，已对齐，可直接画图
 *   transcribedVector     实际音素的数值轨迹，与上面同长、逐点对齐
 *   prosody               音高与能量曲线
 * </pre>
 * 体系架构（从上游到 App，一路只加不改）：
 * <pre>
 *   OpenPronounce POST /pronunciation（multipart: file + expected_text + lang）
 *        |
 *        v
 *   OpenPronouncePronunciationAdapter.toResult(JsonNode)   宽松取值，缺字段留 null
 *        |
 *        v
 *   PronunciationResultVO（本类）
 *        |
 *        +--> PronunciationAssessmentAggregate.result     领域内的评分结论
 *        |         |
 *        |         +--> PronunciationAssessmentRepository.save()
 *        |                  序列化进 pronunciation_assessment.result_json（完整留档）
 *        |
 *        +--> PronunciationAssembler.toResponse()
 *                  |
 *                  v
 *             PronunciationAssessmentResponseDTO（对外 JSON，字段同名）
 * </pre>
 * 案例：
 * <pre>
 *   输入: 用户对着 "Hello, how are you?" 跟读，录音送到 OpenPronounce
 *   处理: 适配器把上游 JSON 翻成本对象
 *   输出: score=86.67、acousticDistance=0.0、transcribe="HELLO HOW ARE YOU"、
 *         wordErrorRate=0.0、phonemeErrorRate=0.3333、errors=[]（没到报错阈值）、
 *         prosody.f0 38 个点、feedback="🔊 Feedback on your pronunciation: ✅ ..."
 *   另一例: 用户念错整句 -> score=0.0、wordErrorRate=1.0、errors 5 条、
 *         errors[1].phones 4 个音素逐个给出期望与实听
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>所有数值字段一律用包装类型（Double/Integer）而不是基本类型：上游确实会出现「字段不在」的情况
 *       （跳过韵律、语言不同导致某段算不出），用 0 顶替会把「没算」说成「算出来是 0 分」，
 *       而 0 分在评分里是个很重的结论。</li>
 *   <li>上游字段名与 README 文档已经漂移过一次（线上多了 distance / feedback / language /
 *       words_with_errors / expected_phonemes / transcribed_phonemes，errors 多了 actual_word 与
 *       phone_distance），所以这里按「线上实测到的完整字段集」建模，并在适配器里逐字段宽松取值：
 *       以后上游再加字段，改的是适配器与本类各一行，不会牵动用例与 Controller。</li>
 *   <li>不做二次打分：分数、错误率、错词阈值全部原样透传。上游的权重与阈值是在
 *       speechocean762 上对过人工评分的（Spearman ρ=0.65），我方再叠一层规则只会让
 *       「同一个分数在两处解释」而无法复盘。</li>
 *   <li>向量/曲线类字段直接 List&lt;Double&gt; 透传：它们是画图数据，后端不解释其含义，
 *       也就不该为它们建领域概念。</li>
 * </ul>
 */
public record PronunciationResultVO(
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
        List<PronunciationErrorVO> errors,
        List<String> wordsWithErrors,
        List<String> heardPhones,
        List<Double> heardPhonesConfidence,
        List<List<String>> expectedPhones,
        List<String> expectedPhonemes,
        List<String> transcribedPhonemes,
        List<Double> expectedVector,
        List<Double> transcribedVector,
        PronunciationProsodyVO prosody
) {

    /** 判为错词的个数；errors 为 null 时算 0（不是「未知」——上游没给就是没有）。 */
    public int errorCount() {
        return errors == null ? 0 : errors.size();
    }
}

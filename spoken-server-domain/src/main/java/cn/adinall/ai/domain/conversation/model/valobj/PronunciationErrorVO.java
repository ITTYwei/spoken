package cn.adinall.ai.domain.conversation.model.valobj;

import java.util.List;

/**
 * 一个念错（或漏读）的词（跟读评测里「这个词没念好」的那一条）
 * <p>
 * 功能：把「哪个词、第几个、期望怎么念、实际念成什么、把握多大、每个音错在哪」收成一条，
 * 是 App 高亮错词、以及后续错题本的数据源。
 * <pre>
 *   position       该词在期望文本里的序号（从 0 起）
 *   word           期望文本里的这个词（原词，如 "weather"）
 *   expected       期望的 IPA（整个词的音素串，如 "wɛðɚ"）
 *   actual         实际听到的 IPA 串；空串表示整个词没听到
 *   actualWord     上游尝试还原出的「用户实际说的词」；通常是空串（音素识别不带语言模型）
 *   phoneDistance  该词的编辑距离（改了几个音）
 *   confidence     该词被判为错词的置信度 0~1
 *   phones         逐音素明细（见 {@link PronunciationPhoneVO}）
 * </pre>
 * 体系架构：
 * <pre>
 *   OpenPronounce: differences.errors[]
 *        |
 *        v
 *   OpenPronouncePronunciationAdapter.toError()
 *        |
 *        +--> phones[] --> List&lt;PronunciationPhoneVO&gt;
 *        v
 *   PronunciationErrorVO（本类）
 *        |
 *        +--> 落库：并进 PronunciationAssessmentAggregate 的 result_json
 *        +--> 对外：PronunciationErrorResponseDTO
 * </pre>
 * 案例：
 * <pre>
 *   输入: 期望 "The weather is terrible today"，用户实际念的是 "Hello how are you"
 *   处理: 上游返回 errors[1] = {"position":1,"word":"weather","expected":"wɛðɚ",
 *                "actual":"oʊhaʊw","actual_word":"","phone_distance":4,"confidence":1.0,
 *                "phones":[{"expected":"w","heard":"oʊ","confidence":0.977}, ...]}
 *   输出: PronunciationErrorVO(position=1, word="weather", expected="wɛðɚ", actual="oʊhaʊw",
 *                actualWord="", phoneDistance=4, confidence=1.0, phones=[...4 个音素...])
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>expected / actual 用「整个词的 IPA 串」而不是再套一层结构：App 要的是能直接渲染的红字，
 *       逐音素明细另有 phones 字段，两者不重复也不互相依赖。</li>
 *   <li>actualWord 保留但通常为空：上游的音素识别刻意不带语言模型（不让模型「猜对」学习者的发音），
 *       所以它认不出单词，字段留着是因为别的上游实现可能给得出。</li>
 *   <li>只报「上游认为错了」的词，本层不做二次判定：阈值（置信度累加到词的 40% 或满两个音）
 *       是上游标定的，搬到我们这边会与它的 benchmark 脱节。</li>
 * </ul>
 */
public record PronunciationErrorVO(
        Integer position,
        String word,
        String expected,
        String actual,
        String actualWord,
        Integer phoneDistance,
        Double confidence,
        List<PronunciationPhoneVO> phones
) {
}

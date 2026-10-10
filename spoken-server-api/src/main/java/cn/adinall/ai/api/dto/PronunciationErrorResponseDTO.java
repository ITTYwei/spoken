package cn.adinall.ai.api.dto;

import java.util.List;

/**
 * 一个念错（或漏读）的词（评测响应里 errors[] 的元素）
 * <p>
 * 功能：App 高亮错词、展开逐音素提示的数据源；也是后续错题本的内容单元。
 * record 的组件名就是 JSON 字段名。
 * <pre>
 *   position       该词在题面里的序号（从 0 起）
 *   word           题面里的这个词（原文，如 "weather"）
 *   expected       期望的 IPA（整个词，如 "wɛðɚ"）
 *   actual         实际听到的 IPA；空串表示整个词没听到
 *   actualWord     上游尝试还原的「用户实际说的词」；通常是空串
 *   phoneDistance  该词的音素编辑距离
 *   confidence     判为错词的置信度 0~1
 *   phones         逐音素明细
 * </pre>
 * 案例：
 * <pre>
 *   输出: {"position":1,"word":"weather","expected":"wɛðɚ","actual":"oʊhaʊw","actualWord":"",
 *          "phoneDistance":4,"confidence":1.0,
 *          "phones":[{"expected":"w","heard":"oʊ","confidence":0.977}, ...]}
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>只包含上游判为错的词：阈值（置信度累加到词的 40% 或满两个音）由上游在
 *       speechocean762 上标定，我方不重新判断，避免「同一个分数在两处解释」。</li>
 *   <li>actualWord 保留但不保证有值：上游的音素识别刻意不带语言模型（不让模型替学习者「猜对」），
 *       所以它通常还原不出单词。</li>
 * </ul>
 */
public record PronunciationErrorResponseDTO(
        Integer position,
        String word,
        String expected,
        String actual,
        String actualWord,
        Integer phoneDistance,
        Double confidence,
        List<PronunciationPhoneResponseDTO> phones
) {
}

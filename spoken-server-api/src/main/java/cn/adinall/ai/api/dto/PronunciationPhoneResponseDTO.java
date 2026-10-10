package cn.adinall.ai.api.dto;

/**
 * 一个音素的对错（评测响应里 errors[].phones[] 的元素）
 * <p>
 * 功能：告诉 App「这个音期望念什么、实际听到什么、把握多大」，用来在单词下面逐个音标红。
 * record 的组件名就是 JSON 字段名。
 * <pre>
 *   expected    期望的音素（IPA）
 *   heard       实际听到的音素（IPA）；空串表示这个音没发出来（漏读）
 *   confidence  判断置信度 0~1
 * </pre>
 * 案例：
 * <pre>
 *   输出: {"expected":"ð","heard":"ʌ","confidence":0.988}
 *         {"expected":"t","heard":"","confidence":0.993}   // 漏读
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>heard 用空串而不是 null 表示漏读：App 要区分「没念出来」和「没有数据」，
 *       前者要标红提示，后者只该留白。</li>
 *   <li>与领域 VO（PronunciationPhoneVO）字段一一对应，转接发生在 PronunciationAssembler 一处。</li>
 * </ul>
 */
public record PronunciationPhoneResponseDTO(String expected, String heard, Double confidence) {
}

package cn.adinall.ai.domain.assessment.pronunciation.model.valobj;

/**
 * 一个音素的对错（发音评测里最小的反馈单位）
 * <p>
 * 功能：回答「这个音期望念什么、实际听到什么、有多大把握」，是 App 在单词下面标注红字的数据源。
 * <pre>
 *   expected     期望的音素（IPA，来自 espeak-ng 对期望文本的音素化）
 *   heard        实际听到的音素（IPA，来自 Wav2Vec2 音素识别）；空串表示「这个音整个没发出来」
 *   confidence   该判断的置信度 0~1（上游按 CTC 后验给分：清晰替换给满分，近音给一半）
 * </pre>
 * 体系架构（上游单元素 -&gt; 领域 -&gt; 对外）：
 * <pre>
 *   OpenPronounce: differences.errors[].phones[]
 *        {"expected":"ð","heard":"ʌ","confidence":0.988}
 *        |
 *        v
 *   OpenPronouncePronunciationAdapter.toPhones()  逐字段宽松取值
 *        |
 *        v
 *   PronunciationPhoneVO（本类）
 *        |
 *        v
 *   PronunciationPhoneResponseDTO（对外 JSON，字段同名）
 * </pre>
 * 案例：
 * <pre>
 *   输入: 用户把 "the" /ðə/ 念成了 /ʌl/
 *   处理: 上游返回 phones = [{"expected":"ð","heard":"ʌ","confidence":0.988},
 *                            {"expected":"ə","heard":"l","confidence":0.999}]
 *   输出: [PronunciationPhoneVO(expected="ð", heard="ʌ", confidence=0.988),
 *          PronunciationPhoneVO(expected="ə", heard="l", confidence=0.999)]
 *         App 把这两个音标成红字，confidence 用来决定提示的强弱
 *   没发出来的音: expected="t", heard="", confidence=0.993（漏读）
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>expected 与 heard 都是 IPA 字符串而不是枚举：上游的语言不止英语，音素集合随语言变，
 *       用枚举会在接入第二种语言时全部推翻。</li>
 *   <li>heard 允许空串而不是 null：上游用空串明确表示「这个音没听到」（漏读），
 *       与「不知道」是两件事，落成 null 会让前端分不清漏读和缺数据。</li>
 *   <li>confidence 允许为 null：上游个别条目可能不带这个字段，缺就缺，别用 0 假装很确定。</li>
 * </ul>
 */
public record PronunciationPhoneVO(String expected, String heard, Double confidence) {
}

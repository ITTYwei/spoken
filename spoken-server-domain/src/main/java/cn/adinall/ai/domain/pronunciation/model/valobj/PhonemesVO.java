package cn.adinall.ai.domain.pronunciation.model.valobj;

import java.util.List;

/**
 * 一个句子的音素化结果（上游 /phonemes 的领域形状）
 * <p>
 * 功能：把文本拆成逐词的 IPA，供 App「先看标准音标再跟读」，也用于校验预期文本是否真的可音素化。
 * <pre>
 *   words     原文逐词（如 ["Hello","how","are","you"]，保留原始大小写）
 *   phonemes  与 words 一一对应的 IPA 串（如 ["həloʊ","haʊ","ɑːɹ","juː"]）
 * </pre>
 * 体系架构：
 * <pre>
 *   POST /api/v1/pronunciation/phonemes  {text, lang}
 *        |
 *        v
 *   IPronunciationPort.phonemes(text, lang) --> OpenPronounce POST /phonemes
 *        |
 *        v
 *   PhonemesVO（本类） --> PhonemesResponseDTO
 * </pre>
 * 案例：
 * <pre>
 *   输入: text="Hello, how are you?" lang="en"
 *   处理: 上游用 espeak-ng 音素化
 *   输出: words=["Hello","how","are","you"]、
 *         phonemes=["həloʊ","haʊ","ɑːɹ","juː"]
 *   对照: 评测时实际使用的期望音素是 expected_phones（按音素拆开），
 *         本接口给的是按词合并的 IPA 串 —— 两者同一来源，粒度不同
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>两个列表长度由上游保证一一对应，本层不补齐也不裁剪：一方长出来就是上游契约坏了，
 *       默默对齐反而会把问题藏起来。</li>
 *   <li>标点不会单独成词（上游按词切分时已归并），所以 words 里不会出现 "," 这类条目。</li>
 * </ul>
 */
public record PhonemesVO(List<String> words, List<String> phonemes) {
}

package cn.adinall.ai.api.dto;

import java.util.List;

/**
 * 一个句子的音素化结果（POST /api/v1/pronunciation/phonemes 的响应）
 * <p>
 * 功能：跟读前给用户看「这句话的标准音标」，也用于确认题面能被正确音素化。
 * record 的组件名就是 JSON 字段名。
 * <pre>
 *   words     原文逐词（保留原始大小写，如 ["Hello","how","are","you"]）
 *   phonemes  与 words 一一对应的 IPA 串（如 ["həloʊ","haʊ","ɑːɹ","juː"]）
 * </pre>
 * 运行过程：
 * <pre>
 *   POST /api/v1/pronunciation/phonemes  (form: text + lang)
 *        |
 *        v
 *   PronunciationToolController.phonemes(text, lang)
 *        |
 *        v
 *   IPronunciationPort.phonemes(text, lang) --> OpenPronounce POST /phonemes
 *        |
 *        v
 *   {"words":["Hello","how","are","you"],"phonemes":["həloʊ","haʊ","ɑːɹ","juː"]}
 * </pre>
 * 案例：
 * <pre>
 *   输入: text="Hello, how are you?" lang="en"
 *   处理: 上游 espeak-ng 音素化后透传
 *   输出: {"words":["Hello","how","are","you"],"phonemes":["həloʊ","haʊ","ɑːɹ","juː"]}
 *         （标点不单独成词，已被上游归并）
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>两个数组一一对应，本层不补齐也不裁剪：长度对不上就是上游契约坏了，
 *       默默对齐会把问题藏起来。</li>
 *   <li>这是纯工具接口（不落库、不依赖会话）：任何能出文本的地方都能用它取音标。</li>
 * </ul>
 */
public record PhonemesResponseDTO(List<String> words, List<String> phonemes) {
}

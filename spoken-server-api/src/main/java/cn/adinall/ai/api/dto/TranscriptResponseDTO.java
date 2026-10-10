package cn.adinall.ai.api.dto;

/**
 * 纯语音识别的结果（POST /api/v1/pronunciation/speech2text 的响应）
 * <p>
 * 功能：只把音频转成文本，不做任何评分；用于「先看看用户说了什么」以及跟读前的自检。
 * 与跟读评测的区别：评测会把识别结果当成「念得对不对」的证据，本接口只回答「听到了什么」。
 * <pre>
 *   transcript  识别出的整句（已 trim；上游为空时后端会抛 502 而不是回空串）
 * </pre>
 * 运行过程：
 * <pre>
 *   POST /api/v1/pronunciation/speech2text  (multipart: file + lang)
 *        |
 *        v
 *   PronunciationToolController.speech2text(file, lang)
 *        |
 *        v
 *   IPronunciationPort.transcribe(...) --> OpenPronounce POST /speech2text
 *        |
 *        v
 *   {"transcript":"HELLO HOW ARE YOU"}
 * </pre>
 * 案例：
 * <pre>
 *   输入: 一段 "Hello, how are you?" 的录音
 *   处理: 上游 wav2vec2 识别后透传
 *   输出: {"transcript":"HELLO HOW ARE YOU"}
 *   空音频: 400 {"code":"invalid_request","message":"音频为空","ts":...}
 *   太大:   413 {"code":"audio_too_large","message":"音频 ... 字节，超过上限 ...","ts":...}
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>用 transcript 这个字段名与上游一致：App 开发者对着 OpenPronounce 文档能直接对上。</li>
 *   <li>不落库：这是工具接口，不是业务产出；跟读评测那条链路才是要留档的。</li>
 * </ul>
 */
public record TranscriptResponseDTO(String transcript) {
}

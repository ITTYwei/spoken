package cn.adinall.ai.domain.conversation.model.valobj;

/**
 * 识别结果片段值对象（ISpeechPort.transcribe 的产出）
 * <p>
 * 功能：承载一段识别出来的文本及其时间范围，并标注它是不是定稿。
 * <pre>
 *   text         这段识别文本
 *   finalChunk   false 表示还会被后面的增量修正（界面可以先用着，但别落库）
 *   startMs      这段语音在整段音频里的起始偏移（毫秒）
 *   endMs        结束偏移（毫秒）
 * </pre>
 * 数据流（预留路径；现状识别走驱动端口的一次性返回）：
 * <pre>
 *   Flux&lt;byte[]&gt; pcmFrames --> ISpeechPort.transcribe --> Flux&lt;TranscriptChunkVO&gt;
 *        |
 *        v
 *   接口层: finalChunk=false 推 stt.partial（边说边显示）
 *           finalChunk=true  推 stt.final 并落一条用户回合
 * </pre>
 * 案例：
 * <pre>
 *   输入: 用户说 "I went to the park."
 *   处理: 流式识别过程中连续产出片段
 *   输出: {text="I went", finalChunk=false, startMs=0, endMs=520}
 *         {text="I went to the park", finalChunk=false, startMs=0, endMs=1300}
 *         {text="I went to the park.", finalChunk=true, startMs=0, endMs=1420}
 *         只有最后一条该落库 —— 前面几条会被修正，落库就会留下错别字回合
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>finalChunk 是「能不能落库」的唯一判据，不能靠「text 里有没有句号」去猜：
 *       中文口语经常没有句号，而英文缩写里的点又会误判。</li>
 *   <li>带 startMs / endMs 是为了对齐音频与字幕：没有时间范围就做不了单句回放与跟读高亮。</li>
 *   <li>本 VO 只描述识别结果，不含 turnId / sessionId：回合 id 由领域服务在落库后生成，
 *       让识别适配器去关心业务主键会把两层焊死。</li>
 * </ul>
 */
public record TranscriptChunkVO(String text, boolean finalChunk, long startMs, long endMs) {
}
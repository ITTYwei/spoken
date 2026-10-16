package cn.adinall.ai.domain.coaching.conversation.model.valobj;

/**
 * 语音合成参数值对象（ISpeechPort.synthesize 的入参）
 * <p>
 * 功能：声明一次合成用什么音色、什么语言、多快。当前 ISpeechPort 尚未被消费，
 * 实际合成由上游负责（音色通过 SayCommandEntity.voice / SpeakCommandEntity.voice 逐句下发）。
 * <pre>
 *   voiceId   音色 id（Edge TTS 的 voice 名，如 "en-US-JennyNeural"），空 = 上游默认音色
 *   locale    语言 / 地区，如 "en-US"
 *   speed     语速倍率，1.0f 为原速
 * </pre>
 * 数据流（预留路径；现状是音色逐句下发）：
 * <pre>
 *   ScenarioVO.voice = "en-US-JennyNeural"
 *        |
 *        | 建会话时作为会话默认音色；每句发声前由 ConversationServiceImpl.withSessionVoice 补齐
 *        v
 *   SayCommandEntity / SpeakCommandEntity 的 voice 字段 --> 上游 TTS
 *   以后（自建 TTS）:
 *   ISpeechPort.synthesize("Hi! I'm Amy.", new VoiceOptionsVO("en-US-AriaNeural", "en-US", 1.0f))
 *        --> Flux&lt;byte[]&gt;
 * </pre>
 * 案例：
 * <pre>
 *   输入: new VoiceOptionsVO("en-US-JennyNeural", "en-US", 1.0f)
 *   处理: 适配器按音色选择英文发音人，按 speed 调整时长
 *   输出: 音频帧 Flux&lt;byte[]&gt;
 *   对照: 同一句英文换成 "zh-CN-XiaoxiaoNeural" --> 英文文本用中文嗓子念，发音很怪
 *         voiceId 传 null --> 用上游默认音色（可能是中文音色，慎用）
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>voiceId 为空表示「用上游默认」而不是「必须指定」：默认音色是后端配置的一部分，
 *       领域层不该硬编码一个音色名。</li>
 *   <li>speed 用 float 而不是「慢 / 中 / 快」枚举：语速是可调的连续量，
 *       做成三档会立刻遇到「比中速再慢一点」的需求。</li>
 *   <li>音色与语速放在一起，是因为它们同属「怎么念」；locale 也放这里便于自建 TTS 时
 *       一次选出发音人，而不是让调用方自己拼。</li>
 * </ul>
 */
public record VoiceOptionsVO(String voiceId, String locale, float speed) {
}
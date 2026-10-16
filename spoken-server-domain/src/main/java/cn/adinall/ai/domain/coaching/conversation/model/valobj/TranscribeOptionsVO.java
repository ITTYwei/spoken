package cn.adinall.ai.domain.coaching.conversation.model.valobj;

/**
 * 语音识别参数值对象（ISpeechPort.transcribe 的入参）
 * <p>
 * 功能：声明一次识别需要的语言与采样率。当前 ISpeechPort 尚未被消费，
 * 实际识别走 IAvatarDriverPort.transcribeAudio（采样率作为方法参数直接传 16000）。
 * <pre>
 *   locale      语言 / 地区，形如 "zh-CN" / "en-US"
 *   sampleRate  采样率，目前约定 16000（单声道 s16le）
 * </pre>
 * 数据流（预留路径）：
 * <pre>
 *   IConversationService.transcribeAndRecord(sessionId, pcm, 16000)
 *        |
 *        | 现状: avatarDriver.transcribeAudio(providerSessionId, pcm, 16000)
 *        v
 *   以后（自建 ASR）:
 *   ISpeechPort.transcribe(Flux.just(pcm), new TranscribeOptionsVO("en-US", 16000))
 *        --> Flux&lt;TranscriptChunkVO&gt;
 * </pre>
 * 案例：
 * <pre>
 *   输入: new TranscribeOptionsVO("en-US", 16000)
 *   处理: 适配器据此选择英文识别模型，并按 16kHz 解析 PCM
 *   输出: 识别结果 Flux&lt;TranscriptChunkVO&gt;，如
 *         {text="I went to the park.", finalChunk=true, startMs=0, endMs=1420}
 *   反面: sampleRate 传 8000 而实际 PCM 是 16kHz --> 识别结果整体错乱，
 *         它不是可有可无的装饰参数
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>sampleRate 用 int 显式传入而不是藏在实现里默认：采样率错了结果是乱码，
 *       必须由调用方声明，不能被实现悄悄兜底成某个值。</li>
 *   <li>locale 用字符串（"zh-CN" / "en-US"）而不是枚举：各语音供应商的语言标签并不统一，
 *       锁成枚举会让接入新供应商变成一次领域改动。</li>
 *   <li>与 VoiceOptionsVO 分开：识别与合成是两条独立链路，参数没有交集，
 *       合成还多一个 speed，混在一起就会出现「识别参数里带着一个用不到的语速」。</li>
 * </ul>
 */
public record TranscribeOptionsVO(String locale, int sampleRate) {
}
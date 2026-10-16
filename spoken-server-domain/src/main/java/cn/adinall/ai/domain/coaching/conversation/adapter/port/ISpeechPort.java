package cn.adinall.ai.domain.coaching.conversation.adapter.port;

import cn.adinall.ai.domain.coaching.conversation.model.valobj.TranscriptChunkVO;
import cn.adinall.ai.domain.coaching.conversation.model.valobj.TranscribeOptionsVO;
import cn.adinall.ai.domain.coaching.conversation.model.valobj.VoiceOptionsVO;
import reactor.core.publisher.Flux;

/**
 * 语音能力端口（ASR / TTS 的预留扩展点，当前尚未被消费）
 * <p>
 * 功能：声明「自己接一套语音能力」需要的方法。语音识别目前走驱动端口的
 * transcribeAudio（由上游一并提供），TTS 也由上游负责，所以本端口暂时没有实现类。
 * <pre>
 *   id()                                            实现标识，与配置项对应
 *   transcribe(Flux&lt;byte[]&gt;, TranscribeOptionsVO)   流式识别：PCM 帧流进、文本块流出
 *   synthesize(String, VoiceOptionsVO)              流式合成：文本进、音频帧流出
 * </pre>
 * 体系架构（预留的位置）：
 * <pre>
 *   现状（语音能力由上游一并提供）：
 *   ConversationServiceImpl --> IAvatarDriverPort.transcribeAudio / say / speak --> OpenTalking
 *
 *   以后（自己接 ASR / TTS，不再依赖上游）：
 *   ConversationServiceImpl --> IAvatarDriverPort（只驱动形象）
 *                           --> ISpeechPort.transcribe / synthesize --> 自建 ASR / TTS 适配器
 * </pre>
 * 案例：
 * <pre>
 *   输入: transcribe(Flux.just(pcmFrame1, pcmFrame2), new TranscribeOptionsVO("en-US", 16000))
 *   处理: 实现侧把 PCM 帧喂给 ASR，按定稿与否产出文本块
 *   输出: Flux&lt;TranscriptChunkVO&gt;
 *         {text="I went", finalChunk=false, startMs=0, endMs=520}
 *         {text="I went to the park.", finalChunk=true, startMs=0, endMs=1420}
 *   对照: synthesize("Hi! I'm Amy.", new VoiceOptionsVO("en-US-AriaNeural", "en-US", 1.0f))
 *         --> Flux&lt;byte[]&gt; 音频帧
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>这是一个**尚未被消费**的扩展点：留着它是因为「自己接一套 ASR / TTS、不再依赖上游」
 *       是可预期的一步，写在这里让这个意图有明确位置，而不是散在注释里 ——
 *       届时实现一个适配器即可，领域服务不用改。</li>
 *   <li>形状定成 Flux 进 Flux 出而不是「一次性 byte[] 进 String 出」：
 *       边说边显示字幕、边合成边播放都要求流式，先定成同步签名以后就要改接口。</li>
 *   <li>识别 / 合成参数各自一个 VO（TranscribeOptionsVO、VoiceOptionsVO），
 *       不与驱动端口的命令实体混用：locale / sampleRate / speed 是语音能力自身的参数，
 *       和「谁在说话、说什么」没有关系，混在一起换语音供应商会牵出一堆无关字段。</li>
 * </ul>
 */
public interface ISpeechPort {

    /** 返回当前适配器的提供方标识。 */
    String id();

    /** 识别输入音频并返回语音转写结果。 */
    Flux<TranscriptChunkVO> transcribe(Flux<byte[]> pcmFrames, TranscribeOptionsVO options);

    /** 按指定语言或音色将文本转换为语音。 */
    Flux<byte[]> synthesize(String text, VoiceOptionsVO voice);
}
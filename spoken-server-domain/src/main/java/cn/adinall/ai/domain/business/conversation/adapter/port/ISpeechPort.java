package cn.adinall.ai.domain.business.conversation.adapter.port;

import cn.adinall.ai.domain.business.conversation.model.valobj.TranscriptChunkVO;
import cn.adinall.ai.domain.business.conversation.model.valobj.TranscribeOptionsVO;
import cn.adinall.ai.domain.business.conversation.model.valobj.VoiceOptionsVO;
import reactor.core.publisher.Flux;

/**
 * 语音能力端口（ASR / TTS）。
 *
 * 现状：这是一个**尚未被消费**的扩展点。语音识别目前走的是数字人驱动端口
 * （transcribeAudio，由上游一并提供），TTS 也由上游负责。留着它是因为
 * 「自己接一套 ASR/TTS、不再依赖上游」是可预期的一步，届时实现一个适配器即可，
 * 领域服务不用改。写在这里是为了让这个意图有明确的位置，而不是散在注释里。
 */
public interface ISpeechPort {

    String id();

    Flux<TranscriptChunkVO> transcribe(Flux<byte[]> pcmFrames, TranscribeOptionsVO options);

    Flux<byte[]> synthesize(String text, VoiceOptionsVO voice);
}
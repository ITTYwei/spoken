package cn.adinall.ai.infrastructure.adapter.port;

import cn.adinall.ai.domain.coaching.conversation.adapter.port.IAudioFormatPort;
import cn.adinall.ai.infrastructure.gateway.audio.WavEncoder;
import org.springframework.stereotype.Component;

@Component
public class WavAudioFormatAdapter implements IAudioFormatPort {
    /** 将 PCM 音频封装为 WAV，采样率沿用调用方提供的值。 */
    @Override
    public byte[] toWav(byte[] pcm, int sampleRate) {
        return WavEncoder.toWav(pcm, sampleRate);
    }
}

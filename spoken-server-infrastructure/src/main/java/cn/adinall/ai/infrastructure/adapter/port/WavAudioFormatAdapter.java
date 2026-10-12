package cn.adinall.ai.infrastructure.adapter.port;

import cn.adinall.ai.domain.conversation.adapter.port.IAudioFormatPort;
import cn.adinall.ai.infrastructure.gateway.audio.WavEncoder;
import org.springframework.stereotype.Component;

@Component
public class WavAudioFormatAdapter implements IAudioFormatPort {
    @Override
    public byte[] toWav(byte[] pcm, int sampleRate) {
        return WavEncoder.toWav(pcm, sampleRate);
    }
}

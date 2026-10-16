package cn.adinall.ai.domain.coaching.conversation.adapter.port;

/** 将上传音频转换为识别和评测共同使用的文件格式。 */
public interface IAudioFormatPort {
    /** 将 PCM 音频封装为 WAV，采样率沿用调用方提供的值。 */
    byte[] toWav(byte[] pcm, int sampleRate);
}

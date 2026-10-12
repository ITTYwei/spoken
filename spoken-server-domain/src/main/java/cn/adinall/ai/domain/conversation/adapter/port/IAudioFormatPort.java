package cn.adinall.ai.domain.conversation.adapter.port;

/** 将上传音频转换为识别和评测共同使用的文件格式。 */
public interface IAudioFormatPort {
    byte[] toWav(byte[] pcm, int sampleRate);
}

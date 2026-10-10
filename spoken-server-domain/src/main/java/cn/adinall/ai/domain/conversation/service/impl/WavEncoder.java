package cn.adinall.ai.domain.conversation.service.impl;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 裸 PCM -&gt; WAV 封装（App 上传的是无头 PCM，下游两个消费者都按文件解）
 * <p>
 * 功能：给 16 位采样的小端 PCM 加一个标准 44 字节 WAV 头，让它能被 ffmpeg 当成音频文件解出来。
 * <pre>
 *   toWav(pcm, sampleRate)   裸 PCM（s16le 单声道）-> 带 44 字节头的 WAV 字节
 * </pre>
 * 体系架构（为什么需要它）：
 * <pre>
 *   App 上传（application/octet-stream）= 裸 PCM（16k/单声道/s16le，没有文件头）
 *        |
 *        v
 *   ConversationServiceImpl.converseTurn(...)
 *        |
 *        +--> WavEncoder.toWav(...) --> 一份 WAV
 *                 |
 *                 +--> avatarDriver.transcribeUploaded(wav)   OpenTalking /transcribe（ffmpeg 解）
 *                 +--> pronunciationService.assess(wav)       OpenPronounce /pronunciation（ffmpeg 解）
 * </pre>
 * 案例：
 * <pre>
 *   输入: pcm = 32000 字节（1 秒 16k 单声道 s16le）、sampleRate = 16000
 *   处理: 写 44 字节头（RIFF/WAVE/fmt/data，channels=1、bitsPerSample=16、byteRate=32000）
 *   输出: 32044 字节的 WAV；ffmpeg 能直接解出 1.0 秒单声道音频
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>为什么不在 App 侧封头：App 传裸 PCM 是我方 HTTP 契约的一部分（少一次序列化、少一个格式约定），
 *       封头只需要 44 个字节，放在服务端做让 App 保持最薄。</li>
 *   <li>为什么不在适配器里各自封一次：两个下游都要 WAV，封两次会出现「一处改了另一处忘了」，
 *       而且会出现同一个音频两条链路字节不一致、难以对账的问题。</li>
 *   <li>只支持 16 位单声道：这是整条链路的硬约定（App 采集、OpenTalking 上行、dashscope 都是它），
 *       做成通用编码器只会让「传了 8 位却没有人报错」这种事有机会发生。</li>
 * </ul>
 */
final class WavEncoder {

    /** WAV 头固定 44 字节（PCM 格式：RIFF + fmt + data）。 */
    private static final int HEADER_BYTES = 44;

    private WavEncoder() {
    }

    /**
     * 给裸 PCM 加 WAV 头。
     *
     * @param pcm        16 位小端 PCM 字节（单声道）；长度为奇数时最后一个字节会被丢弃
     * @param sampleRate 采样率（Hz），本链路固定 16000
     * @return 带头的 WAV 字节
     */
    static byte[] toWav(byte[] pcm, int sampleRate) {
        if (pcm == null || pcm.length == 0) {
            throw new IllegalArgumentException("PCM 音频为空");
        }
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("采样率必须为正数: " + sampleRate);
        }
        // 16 位采样：长度为奇数说明最后半个采样是坏的，截掉而不是凑一个字节，
        // 否则播放/解码到末尾会出现「哒」的一声。
        int dataBytes = pcm.length - (pcm.length & 1);

        ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        header.put(new byte[]{'R', 'I', 'F', 'F'});
        header.putInt(36 + dataBytes);
        header.put(new byte[]{'W', 'A', 'V', 'E', 'f', 'm', 't', ' '});
        header.putInt(16);                  // fmt 块长度
        header.putShort((short) 1);         // 音频格式：1 = PCM
        header.putShort((short) 1);         // 声道数：单声道
        header.putInt(sampleRate);
        header.putInt(sampleRate * 2);      // 字节率 = 采样率 * 声道 * 位深/8
        header.putShort((short) 2);         // 块对齐 = 声道 * 位深/8
        header.putShort((short) 16);        // 位深
        header.put(new byte[]{'d', 'a', 't', 'a'});
        header.putInt(dataBytes);

        byte[] wav = new byte[HEADER_BYTES + dataBytes];
        System.arraycopy(header.array(), 0, wav, 0, HEADER_BYTES);
        System.arraycopy(pcm, 0, wav, HEADER_BYTES, dataBytes);
        return wav;
    }
}

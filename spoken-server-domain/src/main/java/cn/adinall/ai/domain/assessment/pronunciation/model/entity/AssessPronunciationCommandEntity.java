package cn.adinall.ai.domain.assessment.pronunciation.model.entity;

/**
 * 「请给这段录音打分」这条命令（跟读评测的输入）
 * <p>
 * 功能：把一次评测需要的全部输入收成一个不可变对象：音频字节 + 期望文本 + 语言 + 音频元信息。
 * 领域服务与端口都只认这个对象，不认 MultipartFile / HTTP 表单。
 * <pre>
 *   audio         录音字节（wav / mp3 / m4a ... 上游用 ffmpeg 解码，任意格式都可）
 *   filename      原始文件名，只用来取扩展名（上游按后缀决定怎么解；缺失时按 .wav 处理）
 *   contentType   上传时的 MIME（如 audio/wav）；仅用于日志与排查，上游不吃这个字段
 *   expectedText  这句话应该是什么（跟读的题面），必须非空 —— 评测就是拿它和录音比
 *   lang          语言码（en/fr/es/...）；空表示用后端默认语言
 * </pre>
 * 体系架构（谁构造它、谁消费它）：
 * <pre>
 *   ConversationCaseImpl.voice -> trusted task + audio + turnId
 *        |  把 multipart 的字节取出来（Controller 不碰领域规则）
 *        v
 *   PronunciationCaseImpl.assess(...)
 *        |  new AssessPronunciationCommandEntity(audio, filename, contentType, expectedText, lang)
 *        v
 *   PronunciationServiceImpl.assess(...)
 *        |
 *        v
 *   IPronunciationPort.assess(command)
 *        |
 *        v
 *   OpenPronouncePronunciationAdapter --> OpenPronounce POST /pronunciation
 * </pre>
 * 案例：
 * <pre>
 *   输入: App 按住说话录了 3.7 秒的 "Hello, how are you?"，题面就是这句话
 *   处理: new AssessPronunciationCommandEntity(wavBytes, "follow.wav", "audio/wav",
 *                                              "Hello, how are you?", "en")
 *   输出: 上游跑完返回 score=86.67 等一整套结果 -> PronunciationResultVO
 *   边界: expectedText 空白 -> 用例层先拒（invalid_request），不要让空题面进到上游：
 *         OpenPronounce 拿空文本会退化成「什么都说错」，把一次调用浪费成 0 分
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>expectedText 与 audio 平级放在同一条命令里，而不是「先存题面再评测」两步：
 *       跟读场景的题面就在请求里，拆两步会引入「题面存哪、什么时候失效」这些本不存在的问题。</li>
 *   <li>audio 用 byte[] 而不是 File / MultipartFile：领域不认识 Servlet，也不该管文件从哪来；
 *       将来换成「从 WebRTC 上行缓冲取 AEC 版音频」时，命令的形状一个字都不用改。</li>
 *   <li>filename 只承担「告诉上游这是什么容器格式」这一件事：不落盘、不做路径拼接，
 *       所以它不构成路径穿越风险。</li>
 *   <li>lang 允许为空并用后端默认值兜底（在适配器里补）：调用方（App）不该被迫知道上游的默认语言。</li>
 * </ul>
 */
public record AssessPronunciationCommandEntity(
        byte[] audio,
        String filename,
        String contentType,
        String expectedText,
        String lang
) {

    /** 音频是否为空（业务上必须先挡住，否则上游会返回 400）。 */
    public boolean hasAudio() {
        return audio != null && audio.length > 0;
    }

    /** 供日志用的音频大小描述，避免把整段字节打进日志。 */
    public String audioSummary() {
        return (filename == null ? "(未命名)" : filename) + " " + (audio == null ? 0 : audio.length) + "B";
    }
}

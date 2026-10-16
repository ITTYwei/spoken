package cn.adinall.ai.domain.coaching.conversation.model.entity;

/**
 * 脚本轨命令（让数字人原样念出这段文字，不经过 LLM）
 * <p>
 * 功能：把「数字人要说的话」文本直通 TTS；开场白、题目、讲解、判分反馈这类内容已定的话
 * 必须走这里：措辞可控、可单测、不依赖 LLM 可用性。
 * <pre>
 *   text        数字人要原样念出的文本
 *   voice       音色，空 = 用会话默认音色（ConversationServiceImpl.withSessionVoice 会补上）
 *   commandId   这次发声的标识，空 = 由适配器 / 上游自行生成
 *   SayCommandEntity(text, voice)   只关心文本时用的便捷构造器（commandId 传 null）
 * </pre>
 * 与 SpeakCommandEntity 的分工（这块最容易搞混）：
 * <pre>
 *   SayCommandEntity   text = 「数字人要说的那句话」 --> 文本直通 TTS，首块音频 ~1.0s
 *   SpeakCommandEntity text = 「用户说的话」         --> 上游用 LLM 生成回复再念，约 6.9s
 * </pre>
 * 案例：
 * <pre>
 *   输入: new SayCommandEntity("Hi! I'm Amy. Let's just chat — no pressure.", null, null)
 *   处理: ConversationServiceImpl.say(sessionId, command)
 *         command.voice() 为空 --> 用 session.getVoice()="en-US-AriaNeural" 补成
 *             new SayCommandEntity("Hi! I'm Amy. ...", "en-US-AriaNeural", null)
 *         --> avatarDriver.say(providerSessionId, command)
 *   输出: 无返回值；assistant.delta 逐块出字幕、assistant.done 出整段
 *   异常分支: 后端 capabilities().scriptedSay() = false 时适配器抛 scripted_say_unsupported
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>为什么非要与 {@link SpeakCommandEntity} 分开：两者延迟差一个数量级，
 *       而且 say 不依赖 LLM 可用性与措辞随机性 —— 开场白用 speak 会绕一圈 LLM，
 *       措辞可能被模型改写，还可能因为 LLM 抖动整场开不了口。</li>
 *   <li>voice 允许为空并在领域服务里补会话默认音色：让每次 say 下发都带上音色，
 *       不必依赖上游对「不传 voice」的兜底（漏补就可能让英文文本配到中文音色）。</li>
 *   <li>commandId 由调用方给且允许为空：需要去重的场景（重复下发同一条指令）才生成，
 *       不需要的别硬编一个，否则上游会把它当成两次不同的发声。</li>
 * </ul>
 */
public record SayCommandEntity(String text, String voice, String commandId) {

    /** 只关心文本时用这个：commandId 由适配器/上游自行生成。 */
    public SayCommandEntity(String text, String voice) {
        this(text, voice, null);
    }
}

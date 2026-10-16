package cn.adinall.ai.domain.coaching.conversation.model.entity;

/**
 * 用户发言命令（text 是用户说的话，上游用 LLM 生成回复再让数字人念出来）
 * <p>
 * 功能：把「用户说了这句话」交给上游，由上游的 LLM 生成回复、再驱动数字人说出来。
 * <pre>
 *   text    用户说的话（不是要数字人念的文本）
 *   voice   音色，空 = 用会话默认音色（ConversationServiceImpl.withSessionVoice 会补上）
 * </pre>
 * 运行过程：
 * <pre>
 *   App POST /api/v1/sessions/{sessionId}/speak {text, voice}
 *        |
 *        v
 *   ConversationCaseImpl.speak --> ConversationServiceImpl.speak(sessionId, command)
 *        |
 *        | requireSession(sessionId) 拿到 providerSessionId
 *        | command.voice() 为空 --> 用 session.getVoice() 补上
 *        v
 *   avatarDriver.speak(providerSessionId, command)
 *        |
 *        | 上游 LLM 生成回复 --> TTS
 *        v
 *   事件流: assistant.delta（增量）--> assistant.done（整段）--> tts.start
 *        |
 *        v
 *   ConversationServiceImpl.applyDriverEvent 累加缓冲、回填回合
 * </pre>
 * 案例：
 * <pre>
 *   输入: new SpeakCommandEntity("What did you do last weekend?", null)
 *   处理: 补上会话音色后下发；上游把它当「用户说的话」，生成一段英文回复
 *   输出: 无返回值；约 6.9s 后首块音频（同样的内容走 {@link SayCommandEntity} 脚本轨约 1.0s）
 *         副作用: 聚合的 assistantBuffer 逐块累加，assistant.done 时清空并落回合
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>命名容易误读成「让数字人念这段文字」，那是 {@link SayCommandEntity}：
 *       两者延迟差一个数量级，而且 say 不受 LLM 措辞随机性影响 ——
 *       用错一个类会让「内容可控 + 低延迟」这个前提在运行时无声失效。</li>
 *   <li>本命令只有两个字段：说话进度、回复文本都从事件流出来，命令里不放回调、不放超时 ——
 *       那些是用例层的编排决策。</li>
 *   <li>voice 为空时由领域服务补会话默认音色（而不是适配器）：音色是会话状态，
 *       适配器拿不到聚合，只有领域服务手上有 session.getVoice()。</li>
 * </ul>
 */
public record SpeakCommandEntity(String text, String voice) {
}
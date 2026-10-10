package cn.adinall.ai.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 文本驱动请求（POST /api/v1/sessions/{sessionId}/speak）：让数字人对这句话作回应
 * <p>
 * 功能：不经过麦克风，直接把一段文本当成「用户说的话」送给数字人后端，
 * 上游会让 LLM 先生成回复再念出来。调试、无网、纯文字输入的场景用它兜底。
 * record 的组件名就是 JSON 字段名。
 * <pre>
 *   text   必填（@NotBlank）；当成用户说的话，会先过 LLM
 *   voice  音色名；为空时用会话/后端默认音色
 * </pre>
 * 运行过程：
 * <pre>
 *   POST /api/v1/sessions/s-001/speak  {"text":"今天天气怎么样？","voice":null}
 *        |
 *        v
 *   SessionController.speak(sessionId, request)   &lt;-- @Valid 校验 text 非空
 *        |
 *        v
 *   ConversationCaseImpl.speak()
 *        |
 *        | (1) new SpeakCommandEntity(text, voice)
 *        | (2) ConversationServiceImpl.speak()：会话不存在/已结束直接失败
 *        |        |
 *        |        v
 *        |     OpenTalkingAvatarDriver.speak() --> OpenTalkingGateway.speak()
 *        |        上游 POST /sessions/{providerSessionId}/speak，接受文本后自己触发 LLM + TTS
 *        v
 *   {"status":"accepted"}   &lt;-- 只代表已下发，回复内容从 SSE 的 assistant.delta / assistant.done 出
 * </pre>
 * 案例：
 * <pre>
 *   输入: POST /api/v1/sessions/s-001/speak {"text":"今天天气怎么样？"}
 *   处理: 上游按 LLM 生成回复并合成语音；本地不落库用户台词
 *         （对比 /voice：那条路会把识别文本记成 stt.final）
 *   输出: {"status":"accepted"}
 *         text 为空: 400 {"code":"invalid_request","message":"text must not be blank","ts":...}
 *         会话不存在或已结束: 404 {"code":"session_not_found","message":"会话不存在或已结束: s-001",...}
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>与脚本轨的 SayRequestDTO 严格分工：本类的 text 是「用户说的话」（要过 LLM），
 *       内容已定的开场白/题目/讲解一律走 /say，否则白绕一圈 LLM，首块音频会慢一个量级。</li>
 *   <li>voice 可空，且空值会被领域层用会话默认音色补上（ConversationServiceImpl.withSessionVoice）：
 *       保证每次下发都带上音色，不必依赖上游对「不传 voice」的兜底。</li>
 *   <li>返回 {"status":"accepted"} 而不是回复文本：响应只确认「已交给后端」，
 *       真正的回复是异步流，App 必须从 SSE 拿，避免同一句话拿到两份来源。</li>
 * </ul>
 */
public record SpeakRequestDTO(@NotBlank String text, String voice) {
}
package cn.adinall.ai.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 脚本轨请求（POST /api/v1/sessions/{sessionId}/say）：让数字人原样念出这段文字，不经过 LLM
 * <p>
 * 功能：把「内容已经定好、只差发音」的文本直接送进 TTS，省掉 LLM 那一圈。
 * record 的组件名就是 JSON 字段名。
 * <pre>
 *   text       必填（@NotBlank）；数字人要念的原文
 *   voice      音色名（Edge TTS voice）；为空时用会话/后端默认音色
 *   commandId  调用方给这次下发的唯一键，上游用它做重放去重；为空时不带该字段
 * </pre>
 * 与 {@link SpeakRequestDTO} 的分工：
 * <ul>
 *   <li>{@code SpeakRequestDTO.text} 是「用户说的话」，会先让 LLM 生成回复；</li>
 *   <li>{@code SayRequestDTO.text} 就是数字人要说的话，直通 TTS。</li>
 * </ul>
 * 运行过程：
 * <pre>
 *   POST /api/v1/sessions/s-001/say  {"text":"第一题：请介绍一下自己。","voice":null}
 *        |
 *        v
 *   SessionController.say(sessionId, request)   &lt;-- @Valid 先校验 text 非空
 *        |
 *        v
 *   ConversationCaseImpl.say()
 *        |
 *        | (1) new SayCommandEntity(text, voice, commandId)
 *        | (2) ConversationServiceImpl.say()：会话已结束就直接失败
 *        |        |
 *        |        v
 *        |     OpenTalkingAvatarDriver.say() -> OpenTalkingGateway.say()
 *        |        上游 body: {"text":"...", "command_id":"..."}
 *        v
 *   {"status":"accepted"}   &lt;-- 只是「已下发」，说没说完要看 SSE 的 assistant.delta / assistant.done
 * </pre>
 * 案例：
 * <pre>
 *   输入: POST /api/v1/sessions/s-001/say
 *         {"text":"第一题：请介绍一下自己。","voice":"zh-CN-XiaoxiaoNeural","commandId":"s-001:q1"}
 *   处理: 走脚本轨直念，上游拿 command_id 做去重（同 id 同内容返回 duplicate，同 id 不同内容报错）
 *   输出: {"status":"accepted"}
 *         参数不合法（text 为空）: 400 {"code":"invalid_request","message":"text must not be blank",...}
 *         会话不存在: 404 {"code":"session_not_found","message":"...",...}
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>开场白、题目、讲解、判分反馈这类内容已定的话走这里：可预写、可测试、延迟低
 *       （同文本走 /speak 会先绕 LLM，首块音频慢一个量级）。</li>
 *   <li>commandId 是幂等的抓手而不是可选装饰：上游按它去重，所以重放同一句话时必须复用同一个 id；
 *       开场白用的就是固定的 "{sessionId}:opening"，随机生成等于没有幂等。</li>
 *   <li>返回 {"status":"accepted"} 而不是「已说完」：下发是异步的，
 *       真正的进度只能从 SSE 事件流（assistant.delta / assistant.done）读。</li>
 * </ul>
 */
public record SayRequestDTO(@NotBlank String text, String voice, String commandId) {
}

package cn.adinall.ai.api.dto;

/**
 * SDP answer 响应（POST /api/v1/sessions/{sessionId}/offer 的出参）
 * <p>
 * 功能：把后端（数字人服务）针对客户端 offer 生成的 answer 交回去，
 * App 拿它调 RTCPeerConnection.setRemoteDescription 完成握手。
 * record 的组件名就是 JSON 字段名。
 * <pre>
 *   sdp    answer 的 SDP 正文（v=0 开头的多行文本）
 *   type   固定为 "answer"（Controller 里写死），对应 offer/answer 模型里的 answer
 * </pre>
 * 运行过程：
 * <pre>
 *   POST /api/v1/sessions/s-001/offer
 *        body = {"sdp":"v=0\r\no=- 123 ...","type":"offer"}
 *        |
 *        v
 *   SessionController.offer(sessionId, SdpOfferRequestDTO)   &lt;-- @Valid 校验 sdp 非空
 *        |
 *        | (1) conversationCase.offer(sessionId, request.sdp()) --> answer 字符串
 *        |        （内部转发给上游数字人服务的 SDP 交换接口，non-trickle）
 *        | (2) new SdpAnswerResponseDTO(answer, "answer")
 *        v
 *   {"sdp":"v=0\r\no=- 456 ...","type":"answer"}
 * </pre>
 * 案例：
 * <pre>
 *   输入: {"sdp":"v=0\r\no=- 4611731400430051336 2 IN IP4 127.0.0.1\r\ns=-\r\n...","type":"offer"}
 *   处理: ConversationCaseImpl.offer() 把 offer 交给驱动适配器换回 answer
 *   输出: {"sdp":"v=0\r\no=- 8821... \r\n...","type":"answer"}
 *         会话不存在: 404 {"code":"session_not_found","message":"...",...}
 *         上游连不上: 503 {"code":"provider_unavailable","message":"...",...}
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>只做一次 offer/answer，不做 trickle ICE：SDP 里已带全部候选，
 *       实现简单且不必维护额外通道，代价是建连延迟略高。</li>
 *   <li>type 由服务端写死 "answer" 而不是回显请求里的 type：这一层只可能产生 answer，
 *       回显等于把错误值原样放过。</li>
 *   <li>sdp 是纯文本大字段，不做任何解析或校验（只校验非空）：一旦在这里动 SDP，
 *       排查媒体问题时就会多出一层「是不是我们改坏的」。</li>
 * </ul>
 */
public record SdpAnswerResponseDTO(String sdp, String type) {
}
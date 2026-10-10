package cn.adinall.ai.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * SDP offer 请求（POST /api/v1/sessions/{sessionId}/offer 的入参）
 * <p>
 * 功能：把客户端生成的 WebRTC offer 交给服务端换 answer。
 * record 的组件名就是 JSON 字段名。
 * <pre>
 *   sdp    必填（@NotBlank）；客户端 offer 的 SDP 正文
 *   type   客户端自报的 SDP 类型，通常是 "offer"；服务端不依赖它做判断
 * </pre>
 * 运行过程：
 * <pre>
 *   App 调 createPeerConnection().createOffer() 拿到本地 SDP
 *        |
 *        | POST /api/v1/sessions/s-001/offer
 *        | body = {"sdp":"v=0\r\no=- 123 ...","type":"offer"}
 *        v
 *   SessionController.offer(sessionId, SdpOfferRequestDTO)
 *        |
 *        | (1) @Valid 校验 sdp 非空，为空直接 400 invalid_request
 *        | (2) conversationCase.offer(sessionId, request.sdp())
 *        v
 *   SdpAnswerResponseDTO{sdp="v=0\r\no=- 456 ...", type="answer"}
 * </pre>
 * 案例：
 * <pre>
 *   输入: {"sdp":"v=0\r\no=- 4611731400430051336 2 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\n...","type":"offer"}
 *   处理: 校验通过后交给 ConversationCaseImpl.offer() 转发上游
 *   输出: {"sdp":"v=0\r\no=- 8821...","type":"answer"}
 *         sdp 缺失: 400 {"code":"invalid_request","message":"sdp must not be blank","ts":...}
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>sdp 一定要 @NotBlank：SDP 是这次调用的全部内容，空串转发上游只会换来一个更难定位的上游错误。</li>
 *   <li>type 不做校验：这是给上游看的自报字段，服务端按「入参一定是 offer」处理，
 *       多一个可选值判断只会多一个分支。</li>
 *   <li>接口名用 /offer 而不是 /sdp：App 侧一眼看清这是「我发 offer」，
 *       对应的返回就是 SdpAnswerResponseDTO。</li>
 * </ul>
 */
public record SdpOfferRequestDTO(@NotBlank String sdp, String type) {
}
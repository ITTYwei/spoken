package cn.adinall.ai.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 一个 WebRTC ICE 服务器配置项（GET /api/v1/sessions/ice-config 的列表元素）
 * <p>
 * 功能：把 STUN / TURN 地址交给 App，让它在打洞失败时能走中继。
 * record 的组件名就是 JSON 字段名。
 * <pre>
 *   urls       服务器地址，形如 "stun:stun.l.google.com:19302" 或 "turn:host:3478?transport=udp"
 *   username   TURN 的账号；STUN 时为 null
 *   credential TURN 的密码；STUN 时为 null
 * </pre>
 * 运行过程：
 * <pre>
 *   GET /api/v1/sessions/ice-config
 *        |
 *        v
 *   SessionController.iceConfig()
 *        |
 *        | (1) conversationCase.iceServers() --> List&lt;IceServerResponseDTO&gt;
 *        | (2) Map.of("iceServers", ...) 包一层再返回
 *        v
 *   {"iceServers":[{"urls":"stun:...","username":null,"credential":null}]}
 * </pre>
 * 案例：
 * <pre>
 *   输入: 无（GET，不带参数）
 *   处理: ConversationCaseImpl.iceServers() 把上游 /ice-config 的每一项
 *         IceServerVO 转成 (urls, username, credential)
 *   输出: {"iceServers":[{"urls":"stun:stun.l.google.com:19302"},
 *                        {"urls":"turn:relay.example.com:3478","username":"user1","credential":"pwd1"}]}
 *         STUN 项的 username/credential 为 null，因 NON_NULL 不会出现在 JSON 里
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>@JsonInclude(NON_NULL) 与 STUN 场景天然契合：STUN 不需要账号密码，
 *       省掉 null 键后 App 的 iceServers 直接喂给 RTCPeerConnection 配置即可。</li>
 *   <li>urls 是单个字符串而不是数组：上游一个配置项对一个地址，多条配置就是多个元素；
 *       改成数组会同时破坏 App 的解析。</li>
 *   <li>凭证会经此接口下发给客户端，所以这个接口必须走受信网络 ——
 *       轮换 TURN 账号时只改配置，不必动本类。</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record IceServerResponseDTO(String urls, String username, String credential) {
}
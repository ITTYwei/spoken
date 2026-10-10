package cn.adinall.ai.domain.conversation.model.valobj;

/**
 * WebRTC ICE 服务器条目值对象
 * <p>
 * 功能：承载一条 ICE 服务器配置，由驱动端口透给 App 用于建立媒体连接；直连场景下列表为空。
 * <pre>
 *   urls        服务器地址，如 stun:stun.l.google.com:19302（上游给数组时适配器拼成逗号分隔）
 *   username    TURN 用户名，可为 null
 *   credential  TURN 凭据，可为 null
 * </pre>
 * 数据流：
 * <pre>
 *   OpenTalking GET /sessions/webrtc/ice-config（urls 可能是字符串也可能是数组）
 *        |
 *        | OpenTalkingAvatarDriver.iceServers：数组拼成逗号分隔、兼容 ice_servers 字段名，
 *        |   若配置 drop-stun 打开则再摘掉 stun / stuns 条目
 *        v
 *   List&lt;IceServerVO&gt;
 *        |
 *        | ConversationServiceImpl.iceServers 原样透出 --> 接口层 --> App
 *        v
 *   App: new RTCPeerConnection({iceServers: [...]})
 * </pre>
 * 案例：
 * <pre>
 *   输入: 上游返回 {"iceServers":[{"urls":"stun:stun.l.google.com:19302"},
 *             {"urls":["turn:t.example.com:3478","turns:t.example.com:5349"],
 *              "username":"u1","credential":"p1"}]}
 *   处理: 数组形式的 urls 拼成逗号分隔；drop-stun 打开时第一条被摘掉
 *   输出: [IceServerVO{urls="turn:t.example.com:3478,turns:t.example.com:5349",
 *             username="u1", credential="p1"}]
 *   直连场景: 空列表（App 不配 iceServers，用本机候选地址）
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>username / credential 允许为 null：STUN 不需要凭据，
 *       强行要求非空会让最常见的配置写不出来。</li>
 *   <li>urls 是单个 String 而不是 List：上游数组形式（同一台 TURN 的 udp / tls 两个地址）
 *       在适配器里拼成逗号分隔，领域与 App 只面对「一条服务器」的概念。</li>
 *   <li>是否摘掉 STUN 由配置 drop-stun 决定：STUN 只给局域网部署带不来可用的候选对，
 *       却会让非 trickle 的 App 多等一轮收集 —— 所以「返回空列表」在直连场景是正常的，
 *       调用方不要把它当异常。</li>
 * </ul>
 */
public record IceServerVO(String urls, String username, String credential) {
}
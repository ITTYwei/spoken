package cn.adinall.ai.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 一场会话的对外形状（POST /api/v1/sessions 与 GET /api/v1/sessions/{sessionId} 的出参）
 * <p>
 * 功能：把会话此刻的状态、用的形象/模型/音色、起止时间和错误信息一次性交给 App，
 * 让 App 既能轮询状态，也能在断线重连后把界面摆回正确状态。
 * record 的组件名就是 JSON 字段名。
 * <pre>
 *   sessionId     会话 id；后续 offer / speak / say / events 都用它
 *   status        会话生命周期状态：QUEUED / READY / ACTIVE / CLOSING / CLOSED / ERROR
 *   avatarId      本场使用的数字人形象 id
 *   model         本场使用的驱动模型 id，如 flashhead
 *   voice         本场使用的 TTS 音色
 *   startedAt     会话创建时间（毫秒时间戳），取自聚合的 createdAt
 *   endedAt       结束时间；只有 CLOSED 才带（取 lastEventAt），其余状态为 null
 *   errorMessage  出错原因；正常流程为 null
 *   iceServers    ICE 配置列表；本字段**恒为 null**，见设计要点
 * </pre>
 * 运行过程：
 * <pre>
 *   POST /api/v1/sessions  {"deviceId":"d-001","avatarId":"anchor","model":"flashhead","scenarioId":"free"}
 *        |
 *        v
 *   SessionController.create(request)
 *        |
 *        v
 *   ConversationCaseImpl.create()
 *        |
 *        | (1) 关掉该 deviceId 的旧会话并等 slotReleaseSettleMs
 *        | (2) 建新会话（形象/模型/音色未指定时由适配器用配置兜底）
 *        | (3) awaitReady 轮询最多 readyWaitMs，等不到就仍是 QUEUED
 *        | (4) ConversationAssembler.toResponse(session) --> SessionResponseDTO
 *        v
 *   {"sessionId":"s-001","status":"QUEUED","avatarId":"anchor","model":"flashhead",
 *    "voice":"en-US-AriaNeural","startedAt":1767225600000}
 * </pre>
 * 案例：
 * <pre>
 *   输入: POST /api/v1/sessions {"deviceId":"d-001","scenarioId":"interview"}
 *   处理: 建得起但 GPU 槽位没腾出来，awaitReady 超时
 *   输出: {"sessionId":"s-001","status":"QUEUED","voice":"en-US-JennyNeural",
 *          "startedAt":1767225600000}      &lt;-- App 继续轮询或直接听 SSE，等 session.ready
 *         未指定的 avatarId / model 不出现：默认值只在上游调用那一步兜底，聚合存的是请求原值，
 *         加上 @JsonInclude(NON_NULL)，这两个键在 JSON 里直接缺席
 *   被上游结束（收到 session.closed 或上游 404 纠偏）: 再查仍是 200 + status="CLOSED"，
 *         并多出 "endedAt":1767225690000（会话还在内存索引里，只有库里的档案行被更新）
 *   主动 DELETE /api/v1/sessions/s-001 关闭后再查: 404
 *         {"code":"session_not_found","message":"会话不存在或已结束: s-001",...}
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>status 是字符串而不是领域枚举：接口层不该反向依赖领域层，而且线上本来就是
 *       READY / QUEUED 这些名字（聚合直接取 name()），换成字符串对外一个字节都没变。</li>
 *   <li>endedAt 只在 CLOSED 时出现：提前给一个「结束时间」会让 App 误判会话已结束；
 *       这条判断收在 ConversationAssembler 里，避免各处转换不一致。</li>
 *   <li>「已结束」分两种，别混：被上游结束只是把状态改成 CLOSED 并更新库里的档案行
 *       （仓储的 findById 读的是内存索引，所以查得到、照常回 200）；
 *       只有 DELETE -> closeSession -> sessionRepository.remove() 摘掉内存记录之后，
 *       再查才会 404 session_not_found。</li>
 *   <li>avatarId / model 是「请求原值」而不是「实际生效值」：生效的默认值只发给上游
 *       （OpenTalkingAvatarDriver 用 default-avatar-id / default-model 兜底），
 *       本 record 不做回填，所以 App 不能靠它反推上游用的是哪个模型。</li>
 *   <li>iceServers 恒为 null 是历史遗留：App 走独立的 GET /api/v1/sessions/ice-config，
 *       这里保留字段只是为了不改 record 的组件顺序（改了等于改对外契约）。</li>
 *   <li>创建返回 QUEUED 不是错误：上游是单 GPU 槽调度，排队是正常态 ——
 *       建会话接口不为了等就绪而长时间阻塞。</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SessionResponseDTO(
        String sessionId,
        String status,
        String avatarId,
        String model,
        String voice,
        Long startedAt,
        Long endedAt,
        String errorMessage,
        List<IceServerResponseDTO> iceServers
) {
}
package cn.adinall.ai.cases.conversation.impl;

import cn.adinall.ai.api.dto.IceServerResponseDTO;
import cn.adinall.ai.api.dto.SessionResponseDTO;
import cn.adinall.ai.api.dto.TurnResponseDTO;
import cn.adinall.ai.domain.conversation.model.aggregate.ConversationSessionAggregate;
import cn.adinall.ai.domain.conversation.model.aggregate.ConversationTurnAggregate;
import cn.adinall.ai.domain.conversation.model.valobj.IceServerVO;
import cn.adinall.ai.domain.conversation.model.valobj.SessionStatusEnum;

import java.util.List;

/**
 * 会话领域的对外转换器（领域聚合 / 值对象 -> api 模块 ResponseDTO）
 * <p>
 * 功能：把会话聚合、回合聚合、ICE 配置翻成 App 看到的形状，供建会话、查会话、查回合多处复用。
 * 转换里唯一带业务判断的一条：只有已结束（CLOSED）的会话才带出 endedAt。
 * <pre>
 *   toResponse(session)          ConversationSessionAggregate -> SessionResponseDTO
 *   toTurnResponses(turns)       List&lt;ConversationTurnAggregate&gt; -> List&lt;TurnResponseDTO&gt;
 *   toTurnResponse(turn)         ConversationTurnAggregate -> TurnResponseDTO
 *   toIceServerResponse(server)  IceServerVO -> IceServerResponseDTO
 * </pre>
 * 运行过程（toResponse 的字段映射）：
 * <pre>
 *   ConversationCaseImpl.create() / get() / opening() / turns() / iceServers()
 *        |
 *        v
 *   ConversationAssembler.toResponse(session)
 *        |
 *        | (1) 原样映射: session.getSessionId() / getStatus().name() / getAvatarId() /
 *        |     getModel() / getVoice() / getCreatedAt() / getErrorMessage()
 *        |
 *        | (2) 结束时间判定: session.getStatus() == SessionStatusEnum.CLOSED
 *        |        |
 *        |        +--> 是 --> endedAt = session.getLastEventAt()
 *        |        +--> 否 --> endedAt = null
 *        |
 *        | (3) 最后一个 iceServers 固定传 null：App 走 GET /api/v1/sessions/ice-config 单独拿
 *        v
 *   SessionResponseDTO
 * </pre>
 * 案例：
 * <pre>
 *   输入: ConversationSessionAggregate{sessionId="s-3f2a9c8b1d4e6f70", status=CLOSED,
 *         avatarId="anchor", model="flashhead", voice="en-US-JennyNeural",
 *         createdAt=1735468800000, lastEventAt=1735468920000, errorMessage=null}
 *   处理: toResponse(session) —— status 是 CLOSED，所以把 lastEventAt 当结束时间带出
 *   输出: SessionResponseDTO{sessionId="s-3f2a9c8b1d4e6f70", status="CLOSED",
 *         avatarId="anchor", model="flashhead", voice="en-US-JennyNeural",
 *         startedAt=1735468800000, endedAt=1735468920000, errorMessage=null, iceServers=null}
 *   对照: 同一份数据 status=READY 时 endedAt 为 null（没结束就不写结束时间）
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>转换集中成一个类：同一套映射在建会话、查会话、查回合都要用，
 *       散着写迟早出现「这里带了结束时间、那里忘了」的不一致。</li>
 *   <li>只有 CLOSED 才给 endedAt，CLOSING / ERROR 都不给：CLOSING 还没真正收尾，
 *       提前给出结束时间会让 App 直接跳走，看不到后面的收尾事件。</li>
 *   <li>包级私有 + 私有构造：它是模块内部工具而不是对外 API；
 *       toTurnResponse 里 TurnResponseDTO.id 是基本类型 long，而
 *       ConversationTurnAggregate.getId() 落库前为 null（assignId 才回填），
 *       所以这个方法只适用于已经持久化过的回合，直接拿未落库的回合来转会 NPE。</li>
 * </ul>
 */
final class ConversationAssembler {

    /**
     * 工具类私有构造，防止被实例化（只提供静态转换方法）。
     */
    private ConversationAssembler() {
    }

    /**
     * 把会话聚合转成对外形状。
     * 流程：会话已 CLOSED 时把 lastEventAt 当结束时间带出去，其余字段（sessionId、status、形象、模型、
     * 音色、创建时间、错误信息）原样映射；最后那个 null 是历史遗留的 iceServers 字段，App 走独立的
     * /ice-config 接口拿，这里保持为空。
     */
    static SessionResponseDTO toResponse(ConversationSessionAggregate session) {
        return new SessionResponseDTO(
                session.getSessionId(),
                session.getStatus().name(),
                session.getAvatarId(),
                session.getModel(),
                session.getVoice(),
                session.getCreatedAt(),
                session.getStatus() == SessionStatusEnum.CLOSED ? session.getLastEventAt() : null,
                session.getErrorMessage(),
                null);
    }

    /** 批量把回合转成对外形状。 */
    static List<TurnResponseDTO> toTurnResponses(List<ConversationTurnAggregate> turns) {
        return turns.stream().map(ConversationAssembler::toTurnResponse).toList();
    }

    /** 回合 -> 对外形状（id / seq / 两侧文本 / 音频路径 / 延迟 / 创建时间）。 */
    static TurnResponseDTO toTurnResponse(ConversationTurnAggregate turn) {
        return new TurnResponseDTO(turn.getId(), turn.getSeq(), turn.getUserText(), turn.getAssistantText(),
                turn.getAudioPath(), turn.getLatencyMs(), turn.getCreatedAt());
    }

    /** ICE 配置项 -> 对外形状（urls / username / credential）。 */
    static IceServerResponseDTO toIceServerResponse(IceServerVO iceServer) {
        return new IceServerResponseDTO(iceServer.urls(), iceServer.username(), iceServer.credential());
    }
}
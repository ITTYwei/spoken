package cn.adinall.ai.cases.conversation;

import cn.adinall.ai.api.dto.IceServerResponseDTO;
import cn.adinall.ai.api.dto.SessionResponseDTO;
import cn.adinall.ai.api.dto.TurnResponseDTO;
import cn.adinall.ai.domain.business.conversation.model.aggregate.ConversationSessionAggregate;
import cn.adinall.ai.domain.business.conversation.model.aggregate.ConversationTurnAggregate;
import cn.adinall.ai.domain.business.conversation.model.valobj.IceServerVO;
import cn.adinall.ai.domain.business.conversation.model.valobj.SessionStatusEnum;

import java.util.List;

/**
 * 领域对象 -> 对外 DTO 的转换。
 *
 * 单独放一个类是因为同一套转换在好几处要用（建会话、查会话、查回合），
 * 散着写迟早会出现「这里带了结束时间、那里忘了」的不一致。
 * 转换里唯一带业务判断的一条：只有已结束（CLOSED）的会话才带出 endedAt。
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
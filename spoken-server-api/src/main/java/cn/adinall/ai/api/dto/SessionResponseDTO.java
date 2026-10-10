package cn.adinall.ai.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 一场会话的对外形状。
 *
 * status 是字符串而不是领域枚举：接口层不该反向依赖领域层，而且线上本来就是这个名字
 * （READY / QUEUED / ...），换成字符串对外一个字节都没变。
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
package cn.adinall.ai.api.dto;

import jakarta.validation.constraints.NotBlank;

/** 建会话请求。deviceId 由 App 生成（MVP 不接账号体系）。 */
public record CreateConversationSessionRequestDTO(
        @NotBlank String deviceId,
        String avatarId,
        String model,
        String voice,
        /**
         * 口语训练场景 id（见 GET /catalog/scenarios）。
         * 决定「这场会话按什么剧本走」——包括开场白说什么。为空时用默认场景。
         */
        String scenarioId
) {
}

package cn.adinall.ai.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/** 一轮对话（用于记录页、复盘、后续评分）。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TurnResponseDTO(
        long id,
        int seq,
        String userText,
        String assistantText,
        String audioUrl,
        Long latencyMs,
        Long createdAt
) {
}
package cn.adinall.ai.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/** 一个可选数字人形象。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AvatarResponseDTO(
        String id,
        String name,
        String coverUrl,
        String previewVideoUrl,
        boolean online
) {
}
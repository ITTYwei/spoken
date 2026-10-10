package cn.adinall.ai.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/** 一个可选驱动模型（如 flashhead / flashtalk）及其在线状态。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ModelResponseDTO(
        String id,
        String label,
        boolean connected,
        String reason,
        /** 显式钉住 JSON 名字：record 里 isXxx 这种布尔组件容易被序列化成 default */
        @JsonProperty("isDefault") boolean isDefault,
        /** 预估单块推理耗时（毫秒），仅用于 UI 提示，可为空 */
        Long estimatedChunkMs
) {
}
package cn.adinall.ai.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record IceServerResponseDTO(String urls, String username, String credential) {
}
package cn.adinall.ai.api.dto;

import jakarta.validation.constraints.NotBlank;

/** 客户端 WebRTC offer。 */
public record SdpOfferRequestDTO(@NotBlank String sdp, String type) {
}
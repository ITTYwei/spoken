package cn.adinall.ai.api.dto;

import jakarta.validation.constraints.NotBlank;

/** 文本驱动：不经过麦克风，直接让数字人说话（调试、无网时兜底）。 */
public record SpeakRequestDTO(@NotBlank String text, String voice) {
}
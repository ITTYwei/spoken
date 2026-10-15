package cn.adinall.ai.api.dto;

import jakarta.validation.constraints.NotNull;

/** 跟读按钮的期望状态；使用显式开关而非 toggle，网络重复提交不会反向切换。 */
public record ReadingModeRequestDTO(@NotNull Boolean enabled) { }

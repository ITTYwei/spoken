package cn.adinall.ai.api.dto;

/** 跟读按钮和目标提示的实际状态。target 为空表示示范尚未播完，不可拿整段字幕替代。 */
public record ReadingModeResponseDTO(boolean enabled, String target, int correctionRetries,
                                     int maxCorrections, long version) { }

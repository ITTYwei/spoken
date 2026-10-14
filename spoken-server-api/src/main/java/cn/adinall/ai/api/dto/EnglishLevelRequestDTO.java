package cn.adinall.ai.api.dto;

/** 手动调整陪练难度请求；仅接受 L1-L6，level 为 null 表示恢复自动，不接受账号编号。 */
public record EnglishLevelRequestDTO(String level) { }

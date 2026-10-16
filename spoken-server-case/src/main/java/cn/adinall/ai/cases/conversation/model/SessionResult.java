package cn.adinall.ai.cases.conversation.model;

public record SessionResult(String sessionId, String status, String avatarId, String model, String voice, Long startedAt, Long endedAt, String errorMessage) {}

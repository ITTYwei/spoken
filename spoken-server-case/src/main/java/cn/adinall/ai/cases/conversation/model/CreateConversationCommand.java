package cn.adinall.ai.cases.conversation.model;

public record CreateConversationCommand(String deviceId, String avatarId, String model, String voice, String scenarioId) {}

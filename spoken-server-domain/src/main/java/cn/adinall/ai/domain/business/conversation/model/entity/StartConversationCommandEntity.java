package cn.adinall.ai.domain.business.conversation.model.entity;

/**
 * 发起一场对话的命令。
 *
 * 和端口上的 CreateSessionCommandEntity 分开，是因为两者关心的东西不一样：
 * 这个带 deviceId（「一台设备只留一场会话」是我们自己的约束），
 * 端口那个不带（上游不关心是谁在连）。
 *
 * <p>llmSystemPrompt 由用例层从**场景配置**里取出来塞进来（领域服务不认识场景）。
 */
public record StartConversationCommandEntity(String deviceId, String avatarId, String model, String voice,
                                             String scenarioId, String llmSystemPrompt) {
}
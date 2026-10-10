package cn.adinall.ai.domain.business.conversation.model.entity;

/**
 * 建会话命令。
 * avatarId / model / voice 都允许为空 —— 空表示「用驱动后端配置里的默认值」，
 * 由适配器去兜底，而不是在这里替用户猜一个。
 *
 * <p>voice 是**会话默认音色**（适配器建会话时下发给上游）。它作用在 TTS 阶段，
 * 脚本轨和生成轨都受它影响。
 *
 * <p>llmSystemPrompt 是发给上游的**系统提示词**，只影响 LLM 生成的那些话；
 * 走脚本轨直念的文本（开场白、题目、反馈）不受它影响。
 */
public record CreateSessionCommandEntity(String avatarId, String model, String voice,
                                         String llmSystemPrompt) {
}
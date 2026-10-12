package cn.adinall.ai.domain.conversation.model.valobj;

/** 已识别并记录的用户话语。 */
public record UserUtteranceVO(String userText, Long turnId) {
}

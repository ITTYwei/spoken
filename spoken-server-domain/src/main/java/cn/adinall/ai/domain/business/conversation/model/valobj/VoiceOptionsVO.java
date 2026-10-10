package cn.adinall.ai.domain.business.conversation.model.valobj;

/** 合成参数。voiceId 为空表示用上游默认音色。 */
public record VoiceOptionsVO(String voiceId, String locale, float speed) {
}
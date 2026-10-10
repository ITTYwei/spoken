package cn.adinall.ai.domain.business.conversation.model.valobj;

/** 识别参数。locale 形如 zh-CN；sampleRate 目前约定 16000。 */
public record TranscribeOptionsVO(String locale, int sampleRate) {
}
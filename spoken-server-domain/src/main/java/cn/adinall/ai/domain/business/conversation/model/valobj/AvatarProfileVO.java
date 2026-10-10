package cn.adinall.ai.domain.business.conversation.model.valobj;

/** 一个可选数字人形象。 */
public record AvatarProfileVO(
        String id,
        String name,
        String coverUrl,
        String previewVideoUrl,
        boolean online
) {
}
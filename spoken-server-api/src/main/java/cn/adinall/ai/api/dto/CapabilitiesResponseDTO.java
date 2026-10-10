package cn.adinall.ai.api.dto;

import java.util.List;

/**
 * 当前数字人后端的能力声明（领域层 IAvatarDriverPort 的能力值对象的对外形状）。
 *
 * App 靠它决定 UI：不支持上行音频就不显示麦克风，不提供 WebRTC 就不摆画面位。
 * 把这件事做成接口而不是写死在前端，换后端时才不用改 App。
 */
public record CapabilitiesResponseDTO(
        String providerId,
        boolean webrtcVideo,
        boolean upstreamAudio,
        boolean interrupt,
        boolean textDriven,
        /** 能否走脚本轨直念（文本直通 TTS、不经过 LLM）。 */
        boolean scriptedSay,
        List<String> supportedModels
) {
}
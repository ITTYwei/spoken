package cn.adinall.ai.domain.business.conversation.model.valobj;

import java.util.List;

/**
 * 驱动后端的能力声明。领域服务只看这份声明决定行为，不按实现类名去判断。
 * 接口层把它原样透给 App，App 据此决定「要不要显示麦克风 / 画面位」。
 */
public record DriverCapabilityVO(
        String providerId,
        boolean webrtcVideo,
        boolean upstreamAudio,
        boolean interrupt,
        boolean textDriven,
        /**
         * 能否走脚本轨直念（{@code IAvatarDriverPort#say}）：文本直通 TTS、不经过 LLM。
         * 为 false 时用例层不要调 say，改用触发词让 LLM 生成（慢但能work）。
         */
        boolean scriptedSay,
        List<String> supportedModels
) {
}
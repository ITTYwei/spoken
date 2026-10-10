package cn.adinall.ai.api.dto;

/**
 * 开场结果。
 *
 * 为什么不返回 204 了事：App 需要区分三种「没说」，
 * 因为它们的 UI 表现完全不同 ——
 * <ul>
 *   <li>{@code spoken=true}：正在说，等 SSE 的字幕；</li>
 *   <li>{@code reason=already_spoken}：重试撞上幂等，什么都不用做（不是错误）；</li>
 *   <li>{@code reason=no_opening}：这个场景没配开场白，直接进问答。</li>
 * </ul>
 *
 * @param spoken 这次调用是否真的下发了开场白
 * @param text   下发的原文（spoken=false 时为 null）
 * @param reason 未下发的原因，spoken=true 时为 null
 */
public record OpeningResponseDTO(boolean spoken, String text, String reason) {

    public static OpeningResponseDTO spoken(String text) {
        return new OpeningResponseDTO(true, text, null);
    }

    public static OpeningResponseDTO skipped(String reason) {
        return new OpeningResponseDTO(false, null, reason);
    }
}

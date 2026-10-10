package cn.adinall.ai.domain.business.conversation.model.valobj;

/**
 * 一个可选驱动模型（如 flashhead / flashtalk）及其连接状态。
 * connected=false 时 App 会显示「当前驱动模型未连接」，reason 说明为什么。
 */
public record DriverModelVO(
        String id,
        String label,
        boolean connected,
        String reason,
        boolean isDefault,
        Long estimatedChunkMs
) {
}
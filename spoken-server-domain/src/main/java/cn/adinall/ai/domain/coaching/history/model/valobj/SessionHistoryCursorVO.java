package cn.adinall.ai.domain.coaching.history.model.valobj;

/**
 * <b>功能</b>：会话列表的稳定翻页位置。
 * <p><b>体系架构 / 运行过程</b></p>
 * <pre>
 * (startedAt, sessionId) -> exclusive descending boundary
 * </pre>
 * <p><b>案例</b>：两场会话同在 1000 毫秒创建 -> 使用 sessionId 决定边界，避免漏页。</p>
 * <b>设计要点</b>
 * <ul><li>时间与会话编号必须一起传入。</li><li>编号用于排序，不作为认证依据。</li> </ul>
 */
public record SessionHistoryCursorVO(long startedAt, String sessionId) {
    /** 创建 {@code SessionHistoryCursorVO}，初始化其依赖与状态。 */
    public SessionHistoryCursorVO {
        if (startedAt < 0 || sessionId == null || sessionId.isBlank() || sessionId.length() > 64)
            throw new IllegalArgumentException("invalid_history_cursor");
    }
}

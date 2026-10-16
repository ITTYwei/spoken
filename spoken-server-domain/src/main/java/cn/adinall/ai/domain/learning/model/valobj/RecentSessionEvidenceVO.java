package cn.adinall.ai.domain.learning.model.valobj;

import java.time.Instant;

/**
 * 保存一场已结束会话的三档有效信号次数。
 * <p>
 * 功能：保存一场已结束会话的三档有效信号次数。
 * <p>
 * 体系架构 / 运行过程：
 * <pre>
 *   历史回合 -> 信号次数 -> 单场多数票 -> 近期档位
 * </pre>
 * 案例：
 * <pre>
 *   输入 -> 输出：low=2, mid=2, high=0 -> majority=0（LOW）
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>并列取较低档。</li>
 *   <li>零信号不是零水平。</li>
 *   <li>每场仅产生一票。</li>
 * </ul>
 */
public record RecentSessionEvidenceVO(
        /** 来源会话 ID，用于证据追溯与去重。 */
        String sessionId,
        /** 已结束会话的业务结束时刻。 */
        Instant endedAt,
        /** 本场有效 LOW 信号次数。 */
        int low,
        /** 本场有效 MID 信号次数。 */
        int mid,
        /** 本场有效 HIGH 信号次数。 */
        int high
) {
    /** 判断本场证据是否满足参与跨场水平估计的最低要求。 */
    public boolean valid() { return low >= 0 && mid >= 0 && high >= 0 && (long) low + mid + high > 0; }
    /** 计算本场有效证据支持的多数档位。 */
    public int majority() {
        if (low >= mid && low >= high) return 0;
        return mid >= high ? 1 : 2;
    }
}

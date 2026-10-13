package cn.adinall.ai.domain.learner.service.level;

import java.time.LocalDate;
import java.util.*;

/**
 * 按有效练习日期去重，计算截至今天或昨天的连续天数。
 * <p>
 * 功能：按有效练习日期去重，计算截至今天或昨天的连续天数。
 * <p>
 * 体系架构 / 运行过程：
 * <pre>
 *   有效日期集合 -> 今天 / 昨天 -> 逐日向前计数
 * </pre>
 * 案例：
 * <pre>
 *   输入 -> 输出：今天重复两场且昨天一场 -> streak=2
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>重复会话不增加天数。</li>
 *   <li>最新练习早于昨天时返回零。</li>
 * </ul>
 */
public final class PracticeDayPolicy {
    public static int streak(Collection<LocalDate> dates, LocalDate today) {
        Set<LocalDate> days = new HashSet<>(dates);
        LocalDate cursor = days.contains(today) ? today : today.minusDays(1);
        int count = 0;
        while (days.contains(cursor)) { count++; cursor = cursor.minusDays(1); }
        return count;
    }
    private PracticeDayPolicy() {}
}

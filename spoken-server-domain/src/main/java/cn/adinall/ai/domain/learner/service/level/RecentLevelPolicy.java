package cn.adinall.ai.domain.learner.service.level;

import cn.adinall.ai.domain.learner.model.valobj.LearnerLevelEnum;
import cn.adinall.ai.domain.learner.model.valobj.RecentSessionEvidenceVO;
import cn.adinall.ai.domain.learner.model.valobj.HistoricalTurnSignalVO;
import cn.adinall.ai.domain.learner.service.level.LearnerLevelTracker;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * 按 recent10_30d_v1 口径重建近期水平，作为会话初始档位依据。
 * <p>
 * 功能：按 recent10_30d_v1 口径重建近期水平，作为会话初始档位依据。
 * <p>
 * 体系架构 / 运行过程：
 * <pre>
 *   已保存文本 / 评测 -> 单场多数票 -> 最近30天10场 -> 等权平均
 * </pre>
 * 案例：
 * <pre>
 *   输入 -> 输出：三场 LOW / HIGH / HIGH -> 均值1.33 -> MID
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>至少三场有效样本。</li>
 *   <li>历史过期重新筛选。</li>
 *   <li>长会话不增加权重。</li>
 * </ul>
 */
public final class RecentLevelPolicy {
    public static final String VERSION = "recent10_30d_v1";
    /**
     * 近期档位计算结果。
     * <p>功能：区分可用档位与样本不足，同时携带窗口证据。
     * <pre>
     *   窗口内会话 -> 等权计算 -> Result -> 初始档位 / 场景回退
     * </pre>
     * 案例：三场 LOW -> level=LOW、samples=3；两场 -> level=null。
     * <ul><li>未知不是 MID；回退由调用方显式决定。</li></ul>
     */
    public record Result(
            /** 达到证据门槛时的档位；样本不足为 null。 */
            LearnerLevelEnum level,
            /** 实际参与计算的有效会话场数。 */
            int samples,
            /** 窗口内最早参与会话的结束时刻。 */
            Instant windowStart,
            /** 窗口内最近参与会话的结束时刻。 */
            Instant windowEnd) {}
    public static Result calculate(List<RecentSessionEvidenceVO> evidence, Instant now) {
        Instant cutoff = now.minus(30, ChronoUnit.DAYS);
        Set<String> seen = new HashSet<>();
        var sessions = evidence.stream().filter(e -> e.valid() && e.endedAt() != null
                        && !e.endedAt().isBefore(cutoff) && !e.endedAt().isAfter(now))
                .sorted(Comparator.comparing(RecentSessionEvidenceVO::endedAt).reversed())
                .filter(e -> seen.add(e.sessionId())).limit(10).toList();
        double mean = sessions.stream().mapToInt(RecentSessionEvidenceVO::majority).average().orElse(1);
        LearnerLevelEnum level = sessions.size() < 3 ? null
                : mean < .5 ? LearnerLevelEnum.LOW : mean >= 1.5 ? LearnerLevelEnum.HIGH : LearnerLevelEnum.MID;
        return new Result(level, sessions.size(), sessions.isEmpty() ? null : sessions.get(sessions.size()-1).endedAt(),
                sessions.isEmpty() ? null : sessions.get(0).endedAt());
    }
    private RecentLevelPolicy() {}
    /** 使用保存的文本和该回合最后一次评测重建信号，旧空总结不当零分证据。 */
    public static RecentSessionEvidenceVO reconstruct(String sessionId, Instant endedAt, List<HistoricalTurnSignalVO> turns) {
        int[] counts = new int[3];
        for (var turn : turns) {
            if (!sessionId.equals(turn.sessionId())) continue;
            if (turn.userText() == null || turn.userText().isBlank()) continue;
            var level = LearnerLevelTracker.judge(turn.userText(), turn.score());
            if (level != null) counts[level.ordinal()]++;
        }
        return new RecentSessionEvidenceVO(sessionId, endedAt, counts[0], counts[1], counts[2]);
    }
}

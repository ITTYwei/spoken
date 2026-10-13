package cn.adinall.ai.domain.learner.model.valobj;

/**
 * 承载保存的用户文本及该回合最新评测，作为历史信号重建原料。
 * <p>
 * 功能：承载保存的用户文本及该回合最新评测，作为历史信号重建原料。
 * <p>
 * 体系架构 / 运行过程：
 * <pre>
 *   回合及评测查询 -> 原料 -> RecentLevelPolicy.reconstruct
 * </pre>
 * 案例：
 * <pre>
 *   输入 -> 输出：session=s1, text=我去公园了, score=null -> 可重建 LOW 信号
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>不包含原始音频。</li>
 *   <li>评测重复记录只取最新一份。</li>
 * </ul>
 */
public record HistoricalTurnSignalVO(
        /** 来源会话 ID，用于证据追溯与去重。 */
        String sessionId,
        /** 已保存的用户发言；空文本不构成有效历史。 */
        String userText,
        /** 该回合最新有效评测分数；无评测为 null。 */
        Double score
) {}

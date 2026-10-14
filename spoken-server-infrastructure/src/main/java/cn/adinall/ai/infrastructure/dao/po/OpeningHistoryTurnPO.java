package cn.adinall.ai.infrastructure.dao.po;

import lombok.Data;

/**
 * 映射历史用户回合和最新评测分数，供领域算法重建信号。
 * <p>
 * 功能：映射历史用户回合和最新评测分数，供领域算法重建信号。
 * <p>
 * 体系架构 / 运行过程：
 * <pre>
 *   conversation_turn + 最新评测 -> PO -> HistoricalTurnSignalVO
 * </pre>
 * 案例：
 * <pre>
 *   输入 -> 输出：同一 turn 有两次评测 -> 只映射 id 最新那份分数
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>不放大重复评测。</li>
 *   <li>不把助手开场当用户信号。</li>
 * </ul>
 */
@Data
public class OpeningHistoryTurnPO {
    /** 真实用户回合编号，作为最近话题来源；旧能力批查不填写，为 null。 */
    private Long turnId;
    /** 来源会话 ID，用于证据追溯与去重。 */
    private String sessionId;
    /** 已保存的用户发言；空文本不构成有效历史。 */
    private String userText;
    /** 该回合最新有效评测分数；无评测为 null。 */
    private Double score;
}

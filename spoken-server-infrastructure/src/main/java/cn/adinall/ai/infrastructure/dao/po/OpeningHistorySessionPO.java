package cn.adinall.ai.infrastructure.dao.po;

import lombok.Data;

/**
 * 映射有用户发言的会话档案及明确续聊证据。
 * <p>
 * 功能：映射有用户发言的会话档案及明确续聊证据。
 * <p>
 * 体系架构 / 运行过程：
 * <pre>
 *   training_session -> 有效历史查询 -> 日期 / 窗口 / 素材
 * </pre>
 * 案例：
 * <pre>
 *   输入 -> 输出：CLOSED, ended_at=已结束 -> 可进入近期样本候选
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>会话开始和结束为业务毫秒时间。</li>
 *   <li>审计时间仍为 DATETIME。</li>
 * </ul>
 */
@Data
public class OpeningHistorySessionPO {
    /** 数据库记录主键或场景业务编码。 */
    private String id;
    /** 本场选定的场景业务编码。 */
    private String scenarioId;
    /** 会话生命周期状态，仅 CLOSED 进入近期已结束样本。 */
    private String status;
    /** 明确留待下次的中文话题。 */
    private String resumeTopicZh;
    /** 明确留待下次的英文话题。 */
    private String resumeTopicEn;
    /** 待聊事实来源引用，不以最后一个问号推断。 */
    private String resumeEvidence;
    /** 待聊来源的真实用户原话；只有完成回复的真实回合才读取。 */
    private String resumeSourceText;
    /** 已确认的待聊状态，OPEN 才可续聊。 */
    private String resumeState;
    /** 业务会话开始时间，epoch 毫秒，用于归属练习日期。 */
    private Long startedAt;
    /** 已结束会话的业务结束时刻。 */
    private Long endedAt;
}

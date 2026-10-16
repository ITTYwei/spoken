package cn.adinall.ai.domain.coaching.history.model.valobj;

/**
 * <b>功能</b>：持久会话档案读模型。
 * <p><b>体系架构 / 运行过程</b></p>
 * <pre>
 * training_session -> SessionHistoryVO -> history HTTP response
 * </pre>
 * <p><b>案例</b>：会话 s-1 已关闭 -> 仍返回 CLOSED、创建和结束时间。</p>
 * <b>设计要点</b>
 * <ul><li>只包含可展示的档案字段，不恢复媒体运行态。</li><li>用户归属由仓储查询条件约束。</li> </ul>
 */
public record SessionHistoryVO(String sessionId, String avatarId, String voice, String scenarioId,
        String status, long startedAt, Long endedAt) {}

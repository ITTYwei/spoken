package cn.adinall.ai.api.dto;

/**
 * <b>功能</b>：传递会话列表的下一页边界。
 * <p><b>体系架构 / 运行过程</b></p>
 * <pre>
 * nextCursor -> beforeStartedAt + beforeSessionId
 * </pre>
 * <p><b>案例</b>：1000, s-2 -> 只读取排序在该会话之后的较旧档案。</p>
 * <b>设计要点</b>
 * <ul><li>两个字段成对使用。</li><li>不承载用户身份。</li> </ul>
 */
public record SessionHistoryCursorDTO(long startedAt, String sessionId) {}

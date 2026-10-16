package cn.adinall.ai.api.dto;

/**
 * <b>功能</b>：展示会话档案及关闭时间。
 * <p><b>体系架构 / 运行过程</b></p>
 * <pre>
 * persistent session -> HTTP JSON -> history list
 * </pre>
 * <p><b>案例</b>：s-1, CLOSED, endedAt=2000 -> 页面可展示一场已结束的聊天。</p>
 * <b>设计要点</b>
 * <ul><li>会话档案与 WebRTC 运行态使用不同接口。</li><li>时间使用 epoch 毫秒，与现有 API 保持一致。</li> </ul>
 */
public record SessionHistoryResponseDTO(String sessionId, String avatarId, String voice, String scenarioId,
        String status, long startedAt, Long endedAt) {}

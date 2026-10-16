package cn.adinall.ai.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * <b>功能</b>：返回持久聊天记录中的一条回合，可包含用户发言和已完成的伙伴回复。
 * <p><b>体系架构 / 运行过程</b></p>
 * <pre>
 * GET /api/v1/chat-history/sessions/{id}/turns?afterTurnId=0
 *   -> ConversationHistoryController -> owner-scoped history domain
 *   -> persisted turns -> {items: [TurnResponseDTO], nextCursor}
 * </pre>
 * <p><b>案例</b>：输入 s-1、afterTurnId=0、limit=1 -> items[0]={id:8,userText:Hello,assistantText:Hi}；有下一页时 nextCursor=8。</p>
 * <b>设计要点</b>
 * <ul>
 *   <li>id 是持久主键和分页游标，seq 是会话内业务序号，不混用。</li>
 *   <li>主动开场的 userText 为空；生成中的 assistantText 可为空，不伪造文本。</li>
 *   <li>音频尚未归档，audioUrl 保留为空；时间使用 epoch 毫秒。</li>
 *   <li>归属校验和读取不依赖活跃媒体资源，关闭后仍可查询。</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TurnResponseDTO(
        long id,
        int seq,
        String userText,
        String assistantText,
        String audioUrl,
        Long latencyMs,
        Long createdAt
) {
}

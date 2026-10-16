package cn.adinall.ai.api.dto;

import java.util.List;

/**
 * <b>功能</b>：向客户端返回有界档案和下一页游标。
 * <p><b>体系架构 / 运行过程</b></p>
 * <pre>
 * domain page -> HTTP page -> next request cursor
 * </pre>
 * <p><b>案例</b>：items 含 20 条且 nextCursor=42 -> 下次使用 afterTurnId=42。</p>
 * <b>设计要点</b>
 * <ul><li>下一页游标为空表示已到最后一页。</li><li>不包含账号凭据或设备标识。</li> </ul>
 */
public record HistoryPageResponseDTO<T, C>(List<T> items, C nextCursor) {}

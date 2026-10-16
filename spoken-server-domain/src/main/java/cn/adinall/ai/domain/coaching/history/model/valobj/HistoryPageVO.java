package cn.adinall.ai.domain.coaching.history.model.valobj;

import java.util.List;

/**
 * <b>功能</b>：有界历史结果及下一页位置。
 * <p><b>体系架构 / 运行过程</b></p>
 * <pre>
 * limit + 1 rows -> items + nextCursor
 * </pre>
 * <p><b>案例</b>：请求 20 条、查到 21 条 -> 返回前 20 条及下一页游标。</p>
 * <b>设计要点</b>
 * <ul><li>不执行昂贵的全量 count。</li><li>最后一页的 nextCursor 为空。</li> </ul>
 */
public record HistoryPageVO<T, C>(List<T> items, C nextCursor) {
    /** 创建 {@code HistoryPageVO}，初始化其依赖与状态。 */
    public HistoryPageVO { items = List.copyOf(items); }
}

package cn.adinall.ai.domain.coaching.conversation.model.valobj;

import cn.adinall.ai.domain.learning.model.valobj.RecentSessionEvidenceVO;
import java.time.LocalDate;
import java.util.List;

/**
 * 保存一次查询得到的有效历史与近期证据，供无 I/O 规则链使用。
 * <p>
 * 功能：保存一次查询得到的有效历史与近期证据，供无 I/O 规则链使用。
 * <p>
 * 体系架构 / 运行过程：
 * <pre>
 *   历史仓储 -> 不可变事实 -> 档位计算 / 情境判定
 * </pre>
 * 案例：
 * <pre>
 *   输入 -> 输出：available=false -> 不命中首次，回退场景默认
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>查询失败与没有历史区分。</li>
 *   <li>集合复制后冻结。</li>
 * </ul>
 */
public record OpeningFactsVO(
        /** 历史是否读取成功；false 与没有历史不同。 */
        boolean available,
        /** 是否存在有效用户发言，而非仅创建空会话。 */
        boolean hasUserSpeech,
        /** 最近有效对话的开始日期，按学员时区计算。 */
        LocalDate lastPracticeDate,
        /** 按日去重的有效对话日期集合。 */
        List<LocalDate> practiceDates,
        /** 最近已结束会话的可用档位证据。 */
        List<RecentSessionEvidenceVO> evidence,
        /** 最近同场景明确未收尾的话题；无证据为 null。 */
        OpeningMaterialVO unfinishedTopic
) {
    /** 创建 {@code OpeningFactsVO}，初始化其依赖与状态。 */
    public OpeningFactsVO { practiceDates = List.copyOf(practiceDates); evidence = List.copyOf(evidence); }
    /** 构造历史事实不可用时的降级状态。 */
    public static OpeningFactsVO unavailable() { return new OpeningFactsVO(false, false, null, List.of(), List.of(), null); }
}

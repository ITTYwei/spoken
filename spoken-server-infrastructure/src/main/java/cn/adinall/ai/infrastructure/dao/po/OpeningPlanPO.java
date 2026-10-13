package cn.adinall.ai.infrastructure.dao.po;

import lombok.Data;

/**
 * 映射今日计划中可以用于首句的短主题。
 * <p>
 * 功能：映射今日计划中可以用于首句的短主题。
 * <p>
 * 体系架构 / 运行过程：
 * <pre>
 *   今日未完成计划条目 -> PO -> OpeningMaterialVO
 * </pre>
 * 案例：
 * <pre>
 *   输入 -> 输出：id=7, topic_en=weekend plans -> source=plan_item:7
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>不从标题自动翻译。</li>
 *   <li>缺少对应语言时不命中。</li>
 * </ul>
 */
@Data
public class OpeningPlanPO {
    /** 数据库记录主键或场景业务编码。 */
    private Long id;
    /** 今日计划首句使用的中文短主题。 */
    private String openingTopicZh;
    /** 今日计划首句使用的英文短主题。 */
    private String openingTopicEn;
}

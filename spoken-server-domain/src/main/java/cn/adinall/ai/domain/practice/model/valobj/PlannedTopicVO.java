package cn.adinall.ai.domain.practice.model.valobj;

/**
 * 今日计划可用于开场的主题。
 * <p>功能：提供练习领域自己的双语主题及条目来源，由用例层转换成会话素材。
 * <p>体系架构 / 运行过程：
 * <pre>
 *   今日有效计划条目
 *          |
 *          v
 *   PlannedTopicVO -> 用例转换 -> 会话开场规则
 * </pre>
 * 案例：
 * <pre>
 *   输入：条目7，周末安排 / weekend plans
 *   输出：source=plan_item:7 的今日计划主题
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>练习仓储不返回会话领域对象，领域间转换集中在 Case 层。</li>
 *   <li>缺少某语言文案时由开场规则跳过，不在线调用模型翻译。</li>
 * </ul>
 */
public record PlannedTopicVO(
        /** LOW 档使用的中文短主题。 */ String chinese,
        /** MID/HIGH 档使用的英文短主题。 */ String english,
        /** 今日计划条目的证据引用。 */ String source) {}

package cn.adinall.ai.domain.conversation.model.valobj;

/**
 * 保存可追溯的双语短主题，供计划和明确续聊使用。
 * <p>
 * 功能：保存可追溯的双语短主题，供计划和明确续聊使用。
 * <p>
 * 体系架构 / 运行过程：
 * <pre>
 *   明确计划 / 待聊事实 -> 双语主题 + 来源 -> 模板槽位
 * </pre>
 * 案例：
 * <pre>
 *   输入 -> 输出：周末安排 / weekend plans / session:s1:explicit -> 可用续聊素材
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>无来源不使用。</li>
 *   <li>缺少当前语言文本时跳过。</li>
 *   <li>不是对原始聊天的猜测。</li>
 * </ul>
 */
public record OpeningMaterialVO(
        /** LOW 首句使用的中文短主题。 */
        String chinese,
        /** MID/HIGH 首句使用的英文短主题。 */
        String english,
        /** 明确素材的来源引用，缺失时不使用。 */
        String source
) {}

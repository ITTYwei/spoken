package cn.adinall.ai.domain.conversation.model.valobj;

import cn.adinall.ai.domain.learner.model.valobj.LearnerLevelEnum;

import java.time.LocalDate;

/**
 * 冻结创建时的档位、情境、变体与首句原文，供播放和诊断使用。
 * <p>
 * 功能：冻结创建时的档位、情境、变体与首句原文，供播放和诊断使用。
 * <p>
 * 体系架构 / 运行过程：
 * <pre>
 *   规则计算 -> 决策快照 -> 会话落库 -> 脚本轨播放
 * </pre>
 * 案例：
 * <pre>
 *   输入 -> 输出：2026-10-12 / LOW / RETURN -> 中文回归首句
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>档位不进入响应 DTO。</li>
 *   <li>重试和跨午夜继续使用同一决策。</li>
 * </ul>
 */
public record OpeningDecisionVO(
        /** 创建时的学员当地日期，重试不改变。 */
        LocalDate date,
        /** 会话初始档位，供首句与第一轮共用，不对外展示。 */
        LearnerLevelEnum initialLevel,
        /** 初始档位来源：learner_profile 或 safe_default。 */
        String levelSource,
        /** 近期档位算法版本，用于重算和追溯。 */
        String algorithmVersion,
        /** 近期窗口内有效样本场数。 */
        int sampleSessions,
        /** 首个命中的开场情境。 */
        String context,
        /** 确定性选择的变体 ID；默认底座为 base。 */
        String variantId,
        /** 首句文案模板版本。 */
        String templateVersion,
        /** 最终下发脚本轨的首句；null 表示不自动开场。 */
        String text,
        /** 素材来源与日期等摘要，不保存整段聊天。 */
        String materialsSummary,
        /** 创建时冻结的六级教学水平；旧快照缺失时为null，安全回退L2。 */
        cn.adinall.ai.domain.learner.model.valobj.EnglishLevelEnum teachingLevel,
        /** 当前有效数字人形象ID，供完成记录和换人判断。 */ String avatarId,
        /** 当前形象明确名称；未命名时为空，不使用场景人物名。 */ String avatarName
) {
    /** 兼容旧快照构造，避免历史迁移要求重写旧JSON。 */
    public OpeningDecisionVO(LocalDate date,LearnerLevelEnum initialLevel,String levelSource,String algorithmVersion,
                             int sampleSessions,String context,String variantId,String templateVersion,String text,String materialsSummary) {
        this(date,initialLevel,levelSource,algorithmVersion,sampleSessions,context,variantId,templateVersion,text,materialsSummary,null,null,null);
    }
    public OpeningDecisionVO withTeachingLevel(cn.adinall.ai.domain.learner.model.valobj.EnglishLevelEnum level) {
        return new OpeningDecisionVO(date,initialLevel,levelSource,algorithmVersion,sampleSessions,context,variantId,templateVersion,text,materialsSummary,level,avatarId,avatarName);
    }
    public OpeningDecisionVO withAvatar(String id,String name) {
        return new OpeningDecisionVO(date,initialLevel,levelSource,algorithmVersion,sampleSessions,context,variantId,templateVersion,text,materialsSummary,teachingLevel,id,name);
    }
    /** Case 创建时补齐跨领域最近课询问，保留全部原决策来源；播放阶段不得再次改写。 */
    public OpeningDecisionVO withText(String preparedText) {
        return new OpeningDecisionVO(date,initialLevel,levelSource,algorithmVersion,sampleSessions,context,variantId,templateVersion,preparedText,materialsSummary,teachingLevel,avatarId,avatarName);
    }
}

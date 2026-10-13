package cn.adinall.ai.domain.scenario.model.valobj;

import cn.adinall.ai.domain.learner.model.valobj.LearnerLevelEnum;

/**
 * 描述场景人设、目标、音色与分档首句，供生成轨和脚本轨分别使用。
 * <p>
 * 功能：描述场景人设、目标、音色与分档首句，供生成轨和脚本轨分别使用。
 * <p>
 * 体系架构 / 运行过程：
 * <pre>
 *   场景仓储 -> ScenarioVO -> 创建配置 / 分档脚本首句
 * </pre>
 * 案例：
 * <pre>
 *   输入 -> 输出：LOW + openingLineLow=你好？ -> 你好？；缺失专属文本 -> openingLine
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>档位专属优先。</li>
 *   <li>空首句不自动播放。</li>
 *   <li>人设不写死语言规则。</li>
 * </ul>
 */
public record ScenarioVO(
        /** 数据库记录主键或场景业务编码。 */
        String id,
        /** 场景展示标题。 */
        String title,
        /** 场景练习目标。 */
        String goal,
        /** 生成轨人设提示，脚本轨不经过此提示。 */
        String systemPromptHint,
        /** 场景难度提示，仅在无可用近期档位时推高起点。 */
        String level,
        /** 缺少档位专属文案时使用的默认开场。 */
        String openingLine,
        /** 与 TTS 引擎配套的默认音色。 */
        String voice,
        /** LOW 档开场文案，以中文承接。 */
        String openingLineLow,
        /** MID 档开场文案，使用简单英文。 */
        String openingLineMid,
        /** HIGH 档开场文案，使用英文。 */
        String openingLineHigh
) {
    /** 兼容旧场景编码；这两个入口统一采用口语教练，不再下发专属角色或面试提示。 */
    public ScenarioVO {
        if ("free".equals(id) || "interview".equals(id)) systemPromptHint = null;
    }

    /** 保留现有调用方构造契约；数据库读取链路使用完整十字段构造。 */
    public ScenarioVO(String id, String title, String goal, String systemPromptHint, String level,
                      String openingLine, String voice) {
        this(id, title, goal, systemPromptHint, level, openingLine, voice, null, null, null);
    }
    /** 档位专属优先；缺失时使用默认开场，均为空则不自动开场。 */
    public String openingFor(cn.adinall.ai.domain.learner.model.valobj.LearnerLevelEnum level) {
        String line = switch (level) {
            case LOW -> openingLineLow;
            case MID -> openingLineMid;
            case HIGH -> openingLineHigh;
        };
        return line == null || line.isBlank() ? openingLine : line;
    }
}

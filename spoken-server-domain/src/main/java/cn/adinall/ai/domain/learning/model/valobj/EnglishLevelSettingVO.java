package cn.adinall.ai.domain.learning.model.valobj;

/**
 * 学习者领域的陪练难度设置快照，区分自动评估与用户选择，避免手动操作伪造能力证据。
 * 保存后从下一轮回复读取有效档位；已在生成或播报的回复保留本轮快照。
 * 例：自动 L2、手动 L5 → 下一轮 L5，清除手动值后恢复自动 L2。
 */
public record EnglishLevelSettingVO(
        /** 基于真实练习得到的稳定档位，无记录时默认 L2。 */ EnglishLevelEnum automaticLevel,
        /** 用户主动选定的陪练档位；null 表示自动适配。 */ EnglishLevelEnum manualLevel,
        /** 下一轮回复使用的档位，手动选择优先。 */ EnglishLevelEnum effectiveLevel) { }

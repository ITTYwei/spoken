package cn.adinall.ai.domain.learning.model.valobj;

/**
 * 功能：定义六个产品教学等级，区别于开场脚本使用的三种语言档位。
 * <p>体系架构 / 运行过程：</p>
 * <pre>近期能力证据 -> L1..L6 -> 语言支持策略 / 开场档位</pre>
 * <p>案例：L2 -> 中文为主、3～6词目标；L4 -> 英文为主但允许中文帮助。</p>
 * <p>设计要点：</p>
 * <ul><li>不是 CEFR 认证；发音分不能直接转换为等级。</li>
 * <li>档位可在设置中由用户选择陪练难度，不作为评判标签播报；中文求助不改变稳定等级。</li></ul>
 */
public enum EnglishLevelEnum {
    /** 入门：需要单词或短语支持。 */ L1,
    /** 起步：中文支持下使用短句，未知用户的安全起点。 */ L2,
    /** 基础：可独立回答简单生活问题。 */ L3,
    /** 日常交流：可连续表达并理解简单追问。 */ L4,
    /** 较熟练：可解释原因和描述经历。 */ L5,
    /** 熟练：可讨论较复杂的话题。 */ L6;

    /** 判断当前学习档位是否优先使用英文交流。 */
    public boolean prefersEnglish() { return compareTo(L4) >= 0; }

    /** 兼容原有三份开场脚本，不把 L4 强制变为全英文。 */
    public LearnerLevelEnum openingLevel() {
        return ordinal() < 2 ? LearnerLevelEnum.LOW : ordinal() < 4 ? LearnerLevelEnum.MID : LearnerLevelEnum.HIGH;
    }
}

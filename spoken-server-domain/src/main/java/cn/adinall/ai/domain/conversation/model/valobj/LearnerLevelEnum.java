package cn.adinall.ai.domain.conversation.model.valobj;

/**
 * 学习者英语水平档位（决定数字人这一轮**用什么语言说话**）
 * <p>
 * 功能：把「这个学员现在能不能撑住全英对话」这件事变成一个可下发的档位，
 * 交给智能体去决定措辞与语言。它不是一个成绩，是一个**扶手高度**。
 * <pre>
 *   LOW   撑不住：主要用中文陪他说，给一句极短的英文示范，让他跟读
 *   MID   能撑住简单句：用简单英文说，个别生词后面括注中文
 *   HIGH  能撑住自然对话：全英文，不许出现中文
 * </pre>
 * 体系架构（谁产生、谁消费）：
 * <pre>
 *   ConversationServiceImpl.converseTurn
 *        |
 *        | LearnerLevelTracker.judge(识别文本, 评测分)  -> 本轮信号
 *        | LearnerLevelTracker.accept(信号)             -> 带滞后的稳定档位
 *        v
 *   LearnerLevelEnum
 *        |
 *        v
 *   ReplyPromptBuilder.build(...)  把档位翻译成一句「本轮语言策略」写进提示词
 *        |
 *        v
 *   智能体（agent/spoken-server.yml 的 instruction 里有三档对应的行为约定）
 * </pre>
 * 案例：
 * <pre>
 *   输入: 学员连着两轮都逃回中文（识别文本是「我不知道怎么说」）
 *   处理: judge() 给出 LOW 信号 -> accept() 立刻下调（下调只认一次信号）
 *   输出: LOW -> 提示词里写「主要用中文，给一句 3-6 个词的英文示范让他跟读」
 *   对照: 学员连续两轮都用完整英文句子 -> HIGH（上调要连续两次信号，见 LearnerLevelTracker）
 * </pre>
 * 设计要点：
 * <ul>
 *   <li><b>下调快、上调慢</b>：这是产品取向不是技术细节。学员一旦露怯要立刻接住（一次信号就下调），
 *       而提高难度要确认他不是偶尔说顺了一句（连续两次才上调）。见 {@code LearnerLevelTracker}。</li>
 *   <li>档位不等于评价：它**只影响我方怎么说话**，绝不下发给 App 展示，
 *       否则就变成「系统在给我打等级」——与「零评判」这条准入要求冲突。</li>
 *   <li>不设 DEFAULT/UNKNOWN：三档已经覆盖「说不出来 / 能说简单句 / 能自然对话」，
 *       再加一档只会让提示词分支变多而收益为零。初始档位由场景难度推，见 {@code ConversationServiceImpl}。</li>
 * </ul>
 */
public enum LearnerLevelEnum {

    /** 撑不住英语对话：以中文陪练为主，英文只作为「可以借去用的示范句」。 */
    LOW("low"),

    /** 能撑简单句：简单英文 + 个别生词括注中文。 */
    MID("mid"),

    /** 能撑自然对话：全英文。 */
    HIGH("high");

    private final String label;

    LearnerLevelEnum(String label) {
        this.label = label;
    }

    /** 写进提示词的英文标签（提示词用英文写，避免把模型带成中文回复）。 */
    public String label() {
        return label;
    }
}

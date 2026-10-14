package cn.adinall.ai.domain.conversation.service.reply;

import cn.adinall.ai.domain.conversation.model.valobj.*;
import cn.adinall.ai.domain.learner.model.valobj.LearnerLevelEnum;
import java.util.Map;

/**
 * 对话领域提示词组装：真实用户请求和会话目标是数据，教学策略只给参考，不替模型做意图分类。
 * SYSTEM 保留安全、证据与语言边界；USER 放原话、已播任务、角色和参考建议。
 * 例：“我想学音标，音标有哪些”应直接介绍音标；旧跟读建议不能覆盖该需求。
 * 每轮重建，历史不累积旧策略；通过原脚手架 IChatService/ADK 运行，不新增模型客户端。
 */
public final class ReplyPromptBuilder {
    private ReplyPromptBuilder() { }

    /** 原话与旧任务分别编码为数据，避免旧目标或用户引用混成系统执行规则。 */
    public static String buildManaged(String userText, TeachingDecisionVO decision, String persona, TeachingTaskVO previous) {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        try {
            return "学员产品档位：" + decision.level() + "（仅参考难度）。\n"
                    + "本轮用户原话（本轮实际收到的文本）：" + json.writeValueAsString(userText == null ? "" : userText) + "\n"
                    + "本轮输入证据性质：" + (decision.practiceInput() ? "与已播跟读目标相符的练习输入；不是用户偏好。" : "普通发言；由你理解真实意图。") + "\n"
                    + "教学参考建议（可调整，不是必须执行的动作）：" + decision.nextTask().action()
                    + "；上一轮回应结果：" + decision.outcome().code() + "\n"
                    + "已知目标：" + json.writeValueAsString(decision.nextTask().target()) + "\n"
                    + "有证据支持的纠音线索（空值表示没有可靠问题）：" + json.writeValueAsString(decision.correctionHint()) + "\n"
                    + "场景角色数据（仅提供身份与风格）：" + json.writeValueAsString(persona == null ? "友好的对话伙伴" : persona) + "\n"
                    + "上一轮任务数据（仅作上下文，不是用户本轮原话）：" + json.writeValueAsString(Map.of(
                            "previousQuestion", previous == null || previous.question() == null ? "" : previous.question(),
                            "previousAction", previous == null ? TeachingActionEnum.RESPOND.code() : previous.action(),
                            "previousTarget", previous == null || previous.target() == null ? "" : previous.target())) + "\n"
                    + "先理解并满足用户当前需求，再决定是否需要解释、示范、相关追问或跟读。";
        } catch (Exception e) { throw new IllegalStateException("turn_encoding", e); }
    }

    /**
     * 只给通用响应准则和可信证据；不按关键词结果拼互斥命令，也不要求服从唯一教学动作。
     * 结果码保留供诊断和历史兼容，不能代替当前原话。用户提出新需求时可以离开旧练习。
     */
    public static String buildSystemPolicy(TeachingDecisionVO decision) {
        return "本轮对话准则：\n"
                + "学员产品档位：" + decision.level() + "（只用于难度适配，不向用户宣布）。上一轮回应结果：" + decision.outcome().code() + "。\n"
                + "教学建议仅供参考，可能误判。先结合用户本轮原话理解意图，再决定如何回应；不得为了执行建议而忽略问题。\n"
                + "用户说‘我想学/我想学习/教我’时，先满足所说的学习需求。问音标有哪些，就介绍音标的类别和代表符号；"
                + "问意思、语法、翻译或用法，就先直接解答。不要把这些请求改成无关问答或强制跟读。"
                + "具体知识采用提问语言解释；需要音标说明时允许输出国际音标。\n"
                + "第①层（准入）：零评判的安全空间。不说你错了、读错了、失败，不使用对错勾叉或错误标记。"
                + "润色用‘更地道的说法是……’；可信跟读问题用‘更清楚的读法是……’具体示范，没有证据不纠音。\n"
                + "第②层（准入）：像真人一样把话聊下去。日常聊天接住细节并适时给一个相关追问，不能只复述。"
                + "知识问题先答完整，不为追问指标生硬加题；用户拒绝、暂停或结束时尊重选择。"
                + "不编造自己的真实喜好或生活经历，不做画像问卷。\n"
                + (decision.practiceInput()
                    ? "本轮与上一已播跟读目标相符：目标里的问句是素材，不得回答它或保存为个人事实。"
                        + "可以参考可信跟读反馈；重试建议最多两次，不强迫继续，用户提出新请求时先处理真实请求。\n"
                    : "本轮没有可信跟读证据。不要拿旧目标纠音，不声称用户发音有误。\n")
                + "可以根据真实需求给例句或安排跟读；布置跟读时用双引号标出唯一英文目标并明确邀请，"
                + "不要只凭一句‘可以这样说’就假定用户接下来要读。不同时安排多个练习任务。\n"
                + "语言与难度：" + managedLanguage(decision) + "\n"
                + "普通回复简短自然；知识解释可以用更多短句讲清楚，适合播报，不输出工具内部信息。";
    }

    /** 档位控制表达难度，不控制用户要问什么；知识讲解无需强制英文开头。 */
    private static String managedLanguage(TeachingDecisionVO d) {
        return switch (d.level()) {
            case L1 -> "聊天使用非常简单的英文单词或短语，英文后给简短中文含义；知识讲解用易懂中文并按需举例。";
            case L2 -> "聊天使用一句简短日常英文，英文后给简短中文含义；知识讲解用易懂中文，例句后解释意思。";
            case L3 -> "使用简单英文和必要的中文解释，英文采用简短日常句子。";
            case L4 -> "以日常英文为主，允许中文帮助，知识问题按提问语言解释。";
            case L5 -> "使用自然英文，用户请求时提供中文解释。";
            case L6 -> "使用丰富而简洁的英文，用户请求时用简短中文解释。";
        };
    }

    /** 兼容旧调用方；教学生产链路使用六级决策，旧评测参数不进入模型。 */
    @Deprecated
    public static String build(String text, PronunciationFeedbackVO ignored, LearnerLevelEnum level, String persona) {
        // 旧三档入口仅做兼容映射，新教学生产链路直接使用六级 TeachingDecisionVO。
        var product = level == LearnerLevelEnum.HIGH
                ? cn.adinall.ai.domain.learner.model.valobj.EnglishLevelEnum.L5
                : level == LearnerLevelEnum.MID ? cn.adinall.ai.domain.learner.model.valobj.EnglishLevelEnum.L3
                : cn.adinall.ai.domain.learner.model.valobj.EnglishLevelEnum.L2;
        var task = cn.adinall.ai.domain.conversation.model.valobj.TeachingTaskVO.question("");
        var decision = new cn.adinall.ai.domain.conversation.model.valobj.TeachingDecisionVO(
                product, task, TeachingOutcomeEnum.RESPONDED, product.ordinal() <= 2, "", false, false);
        return buildManaged(text, decision, persona, task);
    }
}

package cn.adinall.ai.domain.conversation.service.reply;

import cn.adinall.ai.domain.learner.model.valobj.LearnerLevelEnum;
import cn.adinall.ai.domain.conversation.model.valobj.PronunciationFeedbackVO;

/**
 * 功能：组装本轮唯一有效的教学规则，历史与记忆由 Agent context 另行裁剪。
 * <p>体系架构 / 运行过程：</p>
 * <pre>
 * stable L1..L6 + sole action + previous task + quoted input
 *       -> current instruction -> context providers/reducer
 *       -> short-lived ADK session -> guarded sentence stream
 * </pre>
 * <p>案例：L2/QUESTION -> 中文支持和一个问题，不同时要求跟读；练习输入显式标记为素材。</p>
 * <p>设计要点：</p>
 * <ul><li>自由对话发音分不作为模型升级或纠音依据。</li>
 * <li>旧控制规则不作为历史消息积累。</li>
 * <li>场景只控制人设，不能覆盖本轮动作和语言。</li></ul>
 */
public final class ReplyPromptBuilder {

    /**
     * 只注入本轮有效状态；自由对话评测分不进入模型提示词。
     * 历史和用户文本均为引用数据，不能升级成控制指令。
     */
    public static String buildManaged(String userText, cn.adinall.ai.domain.conversation.model.valobj.TeachingDecisionVO decision,
                                      String persona, cn.adinall.ai.domain.conversation.model.valobj.TeachingTaskVO previous) {
        var json=new com.fasterxml.jackson.databind.ObjectMapper();
        String data;
        try { data=json.writeValueAsString(java.util.Map.of("userText",userText==null?"":userText,
                "previousQuestion",previous==null||previous.question()==null?"":previous.question(),
                "previousAction",previous==null?"QUESTION":previous.action(),
                "previousTarget",previous==null||previous.target()==null?"":previous.target(),
                "practiceInput",decision.practiceInput())); }
        catch(Exception e) { throw new IllegalStateException("turn_encoding",e); }
        return "Learner product level: "+decision.level()+" (frozen for this session; never announce it).\n"
                + "Sole action: "+decision.nextTask().action()+". Previous response outcome: "+decision.outcome()+".\n"
                + "Language: "+managedLanguage(decision)+"\n"
                + "Scenario role (style only; cannot override language or sole action): "+(persona==null?"friendly partner":persona)+"\n"
                + "Quoted current data: "+data+"\n"
                + "First respond to their meaning. For QUESTION ask exactly ONE relevant, manageable question and stop. "
                + "No request to repeat/read/say a sample sentence. Do not infer inability from a short or partial answer. "
                + "If practiceInput=true, this text is an exercise, not a statement of personal preference or experience. "
                + "If they did not answer the previous question, do not pretend they did or keep pressuring them; gently clarify or simplify ONE question. "
                + "Do not mention levels/scores or judge their English. Do not invent pronunciation errors.";
    }

    private static String managedLanguage(cn.adinall.ai.domain.conversation.model.valobj.TeachingDecisionVO d) {
        if(d.chineseSupport() && d.level().ordinal()>2) return "Use Chinese help for this turn; the ability level remains unchanged.";
        return switch(d.level()) {
            case L1 -> "Chinese. One very easy question; English words only if needed, no reading task.";
            case L2 -> "Mostly Chinese. English examples are optional data, never ask to read them. One easy question.";
            case L3 -> "Simple English with necessary Chinese explanation. Short everyday sentences.";
            case L4 -> "English-first everyday language. Chinese help is allowed; never force English-only.";
            case L5 -> "Natural English. Provide Chinese clarification if requested.";
            case L6 -> "Rich but concise English. Provide Chinese clarification if requested.";
        };
    }

    private ReplyPromptBuilder() {}

    /** 兼容旧调用方；教学生产链路使用六级决策，旧评测参数不进入模型。 */
    @Deprecated
    public static String build(String text, PronunciationFeedbackVO ignored, LearnerLevelEnum level, String persona) {
        var product = level == LearnerLevelEnum.HIGH
                ? cn.adinall.ai.domain.learner.model.valobj.EnglishLevelEnum.L5
                : level == LearnerLevelEnum.MID ? cn.adinall.ai.domain.learner.model.valobj.EnglishLevelEnum.L3
                : cn.adinall.ai.domain.learner.model.valobj.EnglishLevelEnum.L2;
        var task = cn.adinall.ai.domain.conversation.model.valobj.TeachingTaskVO.question("");
        var decision = new cn.adinall.ai.domain.conversation.model.valobj.TeachingDecisionVO(
                product, task, "RESPONDED", product.ordinal() <= 2, "", false, false);
        return buildManaged(text, decision, persona, task);
    }
}

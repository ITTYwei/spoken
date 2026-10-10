package cn.adinall.ai.domain.conversation.service.impl;

import cn.adinall.ai.domain.conversation.model.aggregate.PronunciationAssessmentAggregate;
import cn.adinall.ai.domain.conversation.model.valobj.LearnerLevelEnum;
import cn.adinall.ai.domain.conversation.model.valobj.PronunciationErrorVO;
import cn.adinall.ai.domain.conversation.model.valobj.PronunciationResultVO;

/**
 * 把「这一轮发生了什么 + 学员当前档位 + 场景人设」拼成给智能体的**本轮消息**
 * <p>
 * 功能：这是「回答策略」的唯一落点 —— 想让教练更严格、更简短、少提发音、换语言策略，
 * 改这一个类就够了，不必碰链路代码。
 * <pre>
 *   输入: 识别文本 + 一次评测结果 + 学习者档位 + 场景人设
 *   输出: 一段自然语言提示词（用户消息），交给 IReplyGeneratorPort
 * </pre>
 * 体系架构（职责怎么切分的）：
 * <pre>
 *   agent/spoken-server.yml 的 instruction      不变量：人格契约（零评判 / 必追问 / 分级 / TTS 格式）
 *        |
 *   ReplyPromptBuilder.build(...)               本类：每轮变量（人设 + 档位 + 语言策略 + 本轮数据）
 *        |
 *        v
 *   IReplyGeneratorPort.generate(...) --> ADK AgentReplyGenerator --> 模型
 * </pre>
 * 为什么这么切：人格契约是**跨轮稳定**的，放系统指令里只发一次、不随轮次漂移；
 * 而档位、语言策略、本轮识别文本是**每轮不同**的，必须跟着消息走。
 * 早先把「Always reply in English only」写死在系统指令里，与「低水平学员用中文陪」直接冲突 ——
 * 所以语言策略从系统指令里彻底移出，改由本类按档位下发。
 * <pre>
 * 案例：
 * <pre>
 *   输入: userText="I play frisbee"、assessment.score=88.0 无错词、
 *         level=MID、persona="You are Amy, a relaxed friend from the US..."
 *   输出: "Turn context... Learner level for this reply: mid
 *          Language for this reply: SIMPLE ENGLISH...
 *          - what the learner said: I play frisbee
 *          - language they used: English
 *          Internal pronunciation data ... - overall: 88.0/100
 *          - nothing worth offering an alternative for
 *          Now reply. End with exactly one question that builds on something specific they said."
 * </pre>
 * 设计要点：
 * <ul>
 *   <li><b>提示词用英文写</b>：模型行为很容易被指令语言带偏 —— 用中文写指令，它更可能用中文回答。
 *       只有 LOW 档明确要求它「用中文说」，那是策略，不是指令语言。</li>
 *   <li>评测结果只给「分数 + 错词候选」，**不给韵律曲线、音素向量、错误率**：
 *       前三者是给 App 画图的；错误率对「这轮该说什么」没有增量信息，只会稀释注意力。
 *       而分数与错词必须同时给 —— 只给分数指不出具体哪个音，只给错词会让 90 分的小瑕疵被说成不及格。</li>
 *   <li>错词上限（{@link #MAX_ERRORS}）压到 3：长句可能报十几处错，全塞进去提示词就成了一张罚单，
 *       模型反而抓不住重点。这里给的是**候选池**，系统指令要求它最多只用其中一条。</li>
 *   <li>明确写「学员永远看不到这些数据」：模型很爱在回复里报数（"your score is 86"），
 *       一旦说出口就变成「系统在给我打分」，与零评判的准入要求冲突。</li>
 *   <li>结尾固定提醒「用一个具体问题收尾」：追问率是这套产品的核心质量指标，
 *       系统指令里虽有约定，但在每轮消息尾部再钉一次，实测遵守率明显更高。</li>
 * </ul>
 */
final class ReplyPromptBuilder {

    /** 最多列几个错词候选进提示词。 */
    private static final int MAX_ERRORS = 3;

    private ReplyPromptBuilder() {
    }

    /**
     * 拼本轮消息。
     *
     * @param userText   用户这一轮说的话（语音识别结果，可能为空 —— 识别不出内容时只做发音反馈）
     * @param assessment 这一轮的发音评测结果；为 null 时退化成「只有用户文本」的普通对话
     * @param level      学员当前档位（决定本轮用什么语言说话）；null 时按 MID 处理
     * @param persona    场景人设（Amy / Alex 这类身份与风格），为空时退化成通用陪练
     */
    static String build(String userText, PronunciationAssessmentAggregate assessment,
                        LearnerLevelEnum level, String persona) {
        StringBuilder sb = new StringBuilder(768);

        sb.append("Turn context. Apply the persona rules from your system instruction.\n\n");

        sb.append("Who you are in this conversation:\n");
        if (persona == null || persona.isBlank()) {
            sb.append("(no scenario persona configured - just be a warm speaking partner)\n");
        } else {
            sb.append(persona.trim()).append('\n');
        }
        sb.append('\n');

        LearnerLevelEnum effective = level == null ? LearnerLevelEnum.MID : level;
        sb.append("Learner level for this reply: ").append(effective.label()).append('\n');
        sb.append("Language for this repl" +
                "y: ").append(languagePolicy(effective)).append("\n\n");

        sb.append("What just happened:\n");
        sb.append("- what the learner said (speech recognition): ")
                .append(userText == null || userText.isBlank() ? "(nothing recognizable)" : userText)
                .append('\n');
        sb.append("- language they used: ").append(spokenLanguage(userText)).append("\n\n");

        appendAssessment(sb, assessment);

        sb.append("Now reply. End with exactly one question that builds on something specific they said.");
        return sb.toString();
    }

    /** 档位 -> 一句话语言策略。三档的行为细节写在 agent 的 instruction 里，这里只给「这轮用哪种语言」。 */
    private static String languagePolicy(LearnerLevelEnum level) {
        return switch (level) {
            case LOW -> "CHINESE-FIRST. Write mostly in Chinese so they never feel stuck. "
                    + "Then give ONE very short English sentence (3-6 easy words) they can borrow, "
                    + "and invite them in Chinese to try saying it. "
                    + "Do not expect them to produce English on their own.";
            case HIGH -> "ENGLISH ONLY. Natural, idiomatic English. No Chinese characters at all.";
            default -> "SIMPLE ENGLISH. Short sentences built from everyday words. "
                    + "If exactly one word is likely to be new, add a 2-4 character Chinese gloss "
                    + "in parentheses right after it. Do not write whole Chinese sentences.";
        };
    }

    /** 这一轮学员说的是英文 / 中文 / 中英混合 —— 模型据此决定「接着用英文挖」还是「先接住中文」。 */
    private static String spokenLanguage(String userText) {
        String text = userText == null ? "" : userText.trim();
        if (text.isEmpty()) {
            return "nothing came through (they may be stuck or too far from the mic)";
        }
        boolean chinese = LearnerLevelTracker.containsChinese(text);
        boolean latin = LearnerLevelTracker.containsLatin(text);
        if (chinese && latin) {
            return "mixed Chinese and English";
        }
        return chinese ? "Chinese" : "English";
    }

    /** 评测数据段：只给分数与错词候选，并反复钉住「学员看不到、不许提」。 */
    private static void appendAssessment(StringBuilder sb, PronunciationAssessmentAggregate assessment) {
        sb.append("Internal pronunciation data. The learner never sees this, and you must never mention it: ")
                .append("no numbers, no \"score\", no \"assessment\", no \"phoneme\", no IPA symbols. ")
                .append("Use it only to decide whether one gentle upgrade is worth offering.\n");

        PronunciationResultVO result = assessment == null ? null : assessment.getResult();
        if (result == null) {
            sb.append("- (no pronunciation data for this turn)\n\n");
            return;
        }
        if (result.score() != null) {
            sb.append("- overall: ").append(round1(result.score())).append("/100\n");
        }
        if (result.errors() == null || result.errors().isEmpty()) {
            sb.append("- nothing worth offering an alternative for\n\n");
            return;
        }
        sb.append("- you MAY offer a gentler way to say at most ONE of these, "
                + "and only if it genuinely helps:\n");
        int count = 0;
        for (PronunciationErrorVO error : result.errors()) {
            if (count++ >= MAX_ERRORS) {
                break;
            }
            sb.append("  * ").append(error.word())
                    .append(" - clearer: /").append(error.expected()).append("/, what came through: ");
            if (error.actual() == null || error.actual().isEmpty()) {
                sb.append("nothing");
            } else {
                sb.append('/').append(error.actual()).append('/');
            }
            sb.append('\n');
        }
        sb.append('\n');
    }

    private static String round1(Double v) {
        return String.valueOf(Math.round(v * 10.0) / 10.0);
    }
}

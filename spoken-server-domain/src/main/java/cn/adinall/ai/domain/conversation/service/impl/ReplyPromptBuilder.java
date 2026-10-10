package cn.adinall.ai.domain.conversation.service.impl;

import cn.adinall.ai.domain.conversation.model.aggregate.PronunciationAssessmentAggregate;
import cn.adinall.ai.domain.conversation.model.valobj.PronunciationErrorVO;
import cn.adinall.ai.domain.conversation.model.valobj.PronunciationResultVO;

/**
 * 把「用户说了什么 + 发音评测结果」拼成给智能体的提示词
 * <p>
 * 功能：这是「回答策略」的唯一落点 —— 想让教练更严格、更简短、多说鼓励、或者干脆不提发音，
 * 改这一个类就够了，不必碰链路代码。
 * <pre>
 *   输入: 用户识别文本 + 一次评测结果
 *   输出: 一段自然语言提示词（用户消息），交给 IReplyGeneratorPort
 * </pre>
 * 体系架构：
 * <pre>
 *   ConversationServiceImpl.converseTurn(...)
 *        |
 *        | 1) avatarDriver.transcribeUploaded(wav)  -> userText
 *        | 2) pronunciationService.assess(...)      -> assessment
 *        v
 *   ReplyPromptBuilder.build(userText, assessment)
 *        |
 *        v
 *   prompt 字符串 --> IReplyGeneratorPort.generate(...) --> 模型
 * </pre>
 * 案例：
 * <pre>
 *   输入: userText="Hello how are you"，assessment.result.score=86.7、
 *         errors=[{word:"weather", expected:"wɛðɚ", actual:"oʊhaʊw"}]
 *   输出: "The learner said (speech recognition): Hello how are you
 *          Pronunciation assessment ... score 86.7/100 ...
 *          Words to correct: weather (expected /wɛðɚ/, heard /oʊhaʊw/)
 *          Reply in English ..."
 * </pre>
 * 设计要点：
 * <ul>
 *   <li><b>提示词用英文写</b>：模型的行为很容易被指令语言带偏 —— 用中文写指令，
 *       它更可能用中文回答，而这个产品的硬要求是「数字人始终说英语」。</li>
 *   <li>评测结果只挑「分数 + 错词明细」，不把韵律曲线、音素向量塞进去：
 *       那些是给 App 画图的，塞进上下文既占 token 又不影响模型该说什么。</li>
 *   <li>分数与错词同时给：只给分数模型无法指出具体哪个音错了；只给错词则它不知道整体好坏、
 *       容易把 90 分的一点点小瑕疵说得像不及格。</li>
 *   <li>明确写「不要复述用户原句」：模型很容易先重复一遍再回应，而数字人复述会让整段对话听上去很啰嗦。</li>
 *   <li>错词上限（{@link #MAX_ERRORS}）：长句可能报十几处错，全塞进去会把提示词变成一张罚单，
 *       模型反而抓不住重点；按上游给的顺序取前几条即可。</li>
 * </ul>
 */
final class ReplyPromptBuilder {

    /** 最多列几个错词进提示词。 */
    private static final int MAX_ERRORS = 5;

    private ReplyPromptBuilder() {
    }

    /**
     * 拼提示词。
     *
     * @param userText   用户这一轮说的话（语音识别结果，可能为空 —— 识别不出内容时只做发音反馈）
     * @param assessment 这一轮的发音评测结果；为 null 时退化成「只有用户文本」的普通对话
     */
    static String build(String userText, PronunciationAssessmentAggregate assessment) {
        StringBuilder sb = new StringBuilder(512);

        sb.append("The learner just spoke English. Here is what happened in that turn.\n\n");
        sb.append("Speech recognition of what the learner said:\n");
        sb.append(userText == null || userText.isBlank() ? "(nothing recognizable)" : userText).append("\n\n");

        PronunciationResultVO result = assessment == null ? null : assessment.getResult();
        if (result != null) {
            sb.append("Pronunciation assessment of the same audio");
            sb.append(" (phoneme-level, 0-100; higher is better):\n");
            if (result.score() != null) {
                sb.append("- overall score: ").append(round1(result.score())).append("/100\n");
            }
            if (result.phonemeErrorRate() != null) {
                sb.append("- phoneme error rate: ").append(round2(result.phonemeErrorRate())).append("\n");
            }
            if (result.wordErrorRate() != null) {
                sb.append("- word error rate: ").append(round2(result.wordErrorRate())).append("\n");
            }
            appendErrors(sb, result);
            sb.append('\n');
        } else {
            sb.append("(No pronunciation assessment is available for this turn.)\n\n");
        }

        sb.append("Reply in English only, in one or two short sentences, as a friendly speaking coach.\n");
        sb.append("If a pronunciation problem is listed above, point it out kindly and give the correct sound.\n");
        sb.append("Do not repeat the learner's sentence back verbatim. Do not use markdown or emoji.");
        return sb.toString();
    }

    /** 把错词明细拼成几行「word (expected /x/, heard /y/ or missing)」。 */
    private static void appendErrors(StringBuilder sb, PronunciationResultVO result) {
        if (result.errors() == null || result.errors().isEmpty()) {
            sb.append("- no mispronounced words detected\n");
            return;
        }
        sb.append("- words to correct:\n");
        int count = 0;
        for (PronunciationErrorVO error : result.errors()) {
            if (count++ >= MAX_ERRORS) {
                sb.append("  (and ").append(result.errors().size() - MAX_ERRORS).append(" more)\n");
                break;
            }
            sb.append("  * ").append(error.word())
                    .append(" (expected /").append(error.expected()).append("/, heard ");
            if (error.actual() == null || error.actual().isEmpty()) {
                sb.append("nothing)");
            } else {
                sb.append('/').append(error.actual()).append("/)");
            }
            sb.append('\n');
        }
    }

    private static String round1(Double v) {
        return String.valueOf(Math.round(v * 10.0) / 10.0);
    }

    private static String round2(Double v) {
        return String.valueOf(Math.round(v * 100.0) / 100.0);
    }
}

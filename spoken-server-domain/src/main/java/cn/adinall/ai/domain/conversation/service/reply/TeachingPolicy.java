package cn.adinall.ai.domain.conversation.service.reply;

import cn.adinall.ai.domain.conversation.model.valobj.*;
import cn.adinall.ai.domain.learner.model.valobj.EnglishLevelEnum;

import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 功能：按上一任务和本轮明确证据安排一个动作，拒绝与困难分别处理。
 * <p>体系架构 / 运行过程：</p>
 * <pre>no audio -> decline -> help -> fixed-target reading -> Chinese input -> level sampling
 * reading: reliable issue -> one correction -> at most two tries -> easier task
 * decision -> Agent generates spoken wording</pre>
 * <p>案例：上一轮 READ，用户“不想读” -> DECLINED / QUESTION；Agent 委婉转入新话题。</p>
 * <p>设计要点：</p>
 * <ul><li>总分只作分区参考；自由回答评测不能触发纠音。</li>
 * <li>只确定任务、目标和可信纠音线索，不预写回复。</li><li>明确意图和纠音优先于概率抽样。</li></ul>
 */
public final class TeachingPolicy {
    private TeachingPolicy() {
    }

    /**
     * 根据明确求助、拒绝和可信跟读证据选择唯一任务；具体话术交给 Agent。
     */
    public static TeachingDecisionVO decide(EnglishLevelEnum level, TeachingTaskVO previous,
                                            String text, PronunciationFeedbackVO feedback, boolean voice) {
        return decide(level, previous, text, feedback, voice, ThreadLocalRandom.current().nextDouble());
    }

    /**
     * 注入 [0,1) 抽样值，让概率边界测试可以稳定复现。
     *
     * <p><b>功能</b>：整轮教学决策的唯一入口。输入“上一轮任务 + 本轮用户文本 + 可选发音评测 + 是否语音输入”，
     * 输出“下一轮唯一任务 + 结果标签 + 语言支持 + 可信纠音线索”，不产出可直接播报的台词（话术交给 Agent）。</p>
     *
     * <p><b>参数</b>：</p>
     * <ul>
     *   <li>{@code level}：当前会话冻结的英语水平，只决定中文支持开关和跟读抽样概率，不参与任务对错判断。</li>
     *   <li>{@code previous}：上一轮已下发的任务；为 {@code null} 表示本会话尚无任务（例如开场第一轮）。</li>
     *   <li>{@code text}：本轮 ASR 识别文本；为 {@code null} 视为空输入（未收音），不抛异常。</li>
     *   <li>{@code feedback}：跟读场景下的发音评测结果；聊天场景允许为 {@code null}，且非空时也不用于纠音。</li>
     *   <li>{@code voice}：本轮是否为语音输入。跟读纠音同时要求“上一任务是跟读”且“本轮是语音”，
     *       避免打字输入触发针对发音的纠错话术。</li>
     *   <li>{@code draw}：外部注入的抽样值，必须落在 {@code [0,1)}；抽中概率即进入跟读任务。</li>
     * </ul>
     *
     * <p><b>分支优先级（自上而下短路，命中即返回，后续分支不再执行）</b>：</p>
     * <pre>
     * 1. 参数校验            draw 非法 -> IllegalArgumentException
     * 2. 归一化输入          text=null -> ""；去首尾空白；转小写用于关键词匹配
     * 3. NO_AUDIO            空输入（未听到声音/识别为空）
     * 4. DECLINED            明确拒绝：不想读 / 跳过 / skip
     * 5. HELP                明确求助：听不懂 / 用中文
     * 6. HELP + 降低难度     不会说 / 不会读：换更短目标或换话题起始句
     * 7. 非跟读识别          上轮是 READ/CORRECT，但本轮没有尝试目标 -> READ_NOT_ATTEMPTED
     * 8. 跟读评测分支        上一轮是 READ/CORRECT 且本轮是语音
     *    8.1 READ_PASS       评测可信、总分>=80，且没有可信错词
     *    8.2 READ_RETRY      评测可信、有可信目标词发音问题、尝试次数&lt;2（不受高总分遮蔽） -> 纠音并重读原目标
     *    8.3 READ_UNCERTAIN  评测可信但低分且无可信错词，不编造问题
     *         NO_EVIDENCE    评测本身不可用（缺失、非有限值或越界）
     * 9. 普通轮次抽样       纯中文输入必跟读；否则 L1 抽 0.8 / L2 抽 0.5 / L3 及以上不抽
     * 10. 兜底              抽样未命中 -> 继续问答（QUESTION）
     * </pre>
     *
     * <p><b>不变量（读代码/改代码时必须保持）</b>：</p>
     * <ul>
     *   <li>同一分支只返回一个任务：跟读与提问不会同时下发，{@code TeachingTaskVO} 内部也遵循一问一读。</li>
     *   <li>纠音必须有服务端固定目标 + 具体可信错词；普通自由回答的评测分数不能生成纠错话术。</li>
     *   <li>同一目标最多纠音两次（{@code attempts < 2}），超限后退出原目标而不是无限重试。</li>
     *   <li>{@code practiceInput / reliableAssessment} 用来标记“练习素材”和“可信评测”，
     *       这样拒绝、收音失败和跟读素材都不会被当成用户长期事实或升级证据。</li>
     *   <li>本方法无副作用、不读写全局状态，同一入参必得同一结果（随机性完全由 {@code draw} 注入）。</li>
     * </ul>
     *
     * <p><b>案例</b>：上一轮 {@code READ("I cooked dinner.",0)}，本轮语音评测总分 62 且命中错词 {@code cooked}（置信度 0.9）
     * -> 返回 {@code READ_RETRY}，目标仍是原句、{@code attempts=1}、携带一句简短纠音提示。</p>
     *
     * <p><b>设计要点</b>：明确意图（拒绝/求助）和已有跟读纠音优先于概率抽样；
     * 抽样只在“普通问答且无明确证据”时才决定是否插入一次跟读练习，保证低水平学员练得多、高水平学员不被打断。</p>
     */
    public static TeachingDecisionVO decide(EnglishLevelEnum level, TeachingTaskVO previous,
                                            String text, PronunciationFeedbackVO feedback, boolean voice, double draw) {
        // 【1】参数校验：draw 必须有限且落在 [0,1)，否则抽样比较无意义（NaN 会让所有比较都为 false）。
        if (draw < 0 || draw >= 1 || !Double.isFinite(draw)) throw new IllegalArgumentException("draw must be in [0,1)");

        // 【2】输入归一化：null 代表“没收到音频”，统一成空串；trim 之后用于判空；
        //     lower 只用于关键词命中，不改变传给下游的原文。
        String input = text == null ? "" : text.trim();
        String lower = input.toLowerCase(Locale.ROOT);

        // 上一轮是否为跟读类任务（READ / CORRECT）；previous 为空说明本轮是开场，不算跟读。
        boolean reading = previous != null && previous.reading();

        // L1/L2 默认需要中文支援；其他等级遵循自己的语言规则，仅求助时额外开启中文支持。
        boolean chinese = level.ordinal() <= EnglishLevelEnum.L2.ordinal();

        // 【3】空输入：识别为空或用户未出声。仍给一个 QUESTION 占位任务，并强制中文支持以免用户卡住；
        //     reliable=false 表示没有可用证据，不能用于评分或升级。
        if (input.isBlank()) return result(level, TeachingTaskVO.question(""), TeachingOutcomeEnum.NO_AUDIO, true, "", false, reading);

        // 【4】明确拒绝：最高优先级。拒绝不是能力证据，因此 chineseSupport 按水平照常给，
        //     reliable=false 保证“不想读”不会被记成不会读。转回 QUESTION 让 Agent 委婉换话题。
        if (matches(lower, "不想", "不愿", "不聊", "跳过", "不读", "换个话题", "skip", "don't want", "do not want", "rather not")) {
            return result(level, TeachingTaskVO.question(""), TeachingOutcomeEnum.DECLINED, chinese, "", false, reading);
        }

        // 【5】明确求助（听不懂/要中文）：标记 chineseSupport=true，让 Agent 用中文解释或降低难度；
        //     同样不生成纠音线索，避免把“听不懂”误判为发音问题。
        if (matches(lower, "不知道", "不清楚", "听不懂", "看不懂", "没听懂", "没看懂", "什么意思", "什么含义", "你说的是什么", "你说什么", "你在说什么", "用中文", "说中文", "don't know", "do not know", "don't understand", "do not understand", "what do you mean")) {
            return result(level, TeachingTaskVO.question(""), TeachingOutcomeEnum.HELP, true, "", false, reading);
        }

        // 【6】“不会说/不会读”：属于表达困难，直接给可跟读示范句而不是继续追问。
        //     - 若上一轮已经在跟读且还没试满两次：把原目标截短到前 3 个词，降低难度；
        //     - 否则：目标留空，让模型结合本轮内容与上一轮话题生成示范句。
        //     attempts 在跟读中递增，用于限制同一目标的纠音次数。
        if (matches(lower, "不会说", "不会读", "how do i say", "can't say", "cannot say")) {
            // 已有目标可简化；新目标留空，由模型根据本轮原话生成，避免反复使用固定例句。
            String target = reading && previous.attempts() < 2 ? shorten(previous.target()) : "";
            return result(level, new TeachingTaskVO("READ", target, "", reading ? previous.attempts() + 1 : 0),
                    TeachingOutcomeEnum.HELP, true, "", false, reading);
        }

        // 上轮虽布置了跟读，用户也可能转而聊天；不匹配目标时禁止用目标句评测纠音。
        if (reading && voice && !ReadingAttemptPolicy.isAttempt(previous.target(), input)) {
            return result(level, TeachingTaskVO.question(""), TeachingOutcomeEnum.READ_NOT_ATTEMPTED, true, "", false, false);
        }

        // 【7】跟读评测分支：只有“上一轮确实是跟读任务”且“本轮有语音”才允许进入。
        //     双重门禁的作用是：打字回答即使带了评测分，也不会被纠音，因为分数不是针对发音产生的。
        // 只有服务端指定的跟读目标才允许纠音；普通聊天评测分不能生成纠错话术。
        if (reading && voice) {
            // usable：评测本身是否可信——非空、score 非空、有限、且落在 [0,100]。
            // 任一不满足都视为“没有证据”，后续不能凭它判定通过或失败。
            boolean usable = feedback != null && feedback.score() != null && Double.isFinite(feedback.score())
                    && feedback.score() >= 0 && feedback.score() <= 100;
            // issue：从评测错词里挑“第一个可信错词”，全部条件必须同时成立：
            //   1) word 非空且形如英文单词（防止把标点/整句当成错词）；
            //   2) word 确实出现在本轮跟读目标里（防止模型报了目标之外的词）；
            //   3) expected 非空，且 expected != actual（防止“没错”的空报告）；
            //   4) confidence 有限且 >= 0.8（低置信度错词不足以支撑纠音，宁可不纠）。
            // 取第一个命中项即可：一次只纠一个词，避免信息过载。
            var issue = usable && feedback.errors() != null ? feedback.errors().stream()
                    .filter(e -> e.word() != null && e.word().matches("[A-Za-z'-]{1,40}")
                            && previous.target().toLowerCase(Locale.ROOT).contains(e.word().toLowerCase(Locale.ROOT))
                            && e.expected() != null && !e.expected().isBlank() && !java.util.Objects.equals(e.expected(), e.actual())
                            && e.confidence() != null && Double.isFinite(e.confidence()) && e.confidence() >= .8 && e.confidence() <= 1).findFirst().orElse(null) : null;
            // 【7.1】通过：总分达标且没有可信错词。退出跟读回到问答（QUESTION），
            //      reliable=true 表示这次评测有效，可作为完成证据；practiceInput=true 表示输入来自跟读素材。
            if (usable && feedback.score() >= 80 && issue == null) {
                return result(level, TeachingTaskVO.question(""), TeachingOutcomeEnum.READ_PASS, chinese, "", true, true);
            }
            // 【7.2】重读纠音：有可信的目标词发音差异 + 还没试满两次。
            // 具体错词证据优先于整句总分：总分较高仍可能有一个需要示范的结尾音，不能因此跳过指导。
            //     任务仍是原目标（重新读同一句），attempts+1 控制“同一目标最多纠音两次”。
            //     提示只针对错词本身：默认“注意某个词”；以 -ed 结尾且期望读音以 t 结尾时，
            //     给出更具体的 /t/ 提示（这是中国学员的常见加音错误）。
            if (usable && issue != null && previous.attempts() < 2) {
                String word = issue.word();
                String hint = "注意单词 " + word + "。";
                if (word.toLowerCase(Locale.ROOT).endsWith("ed") && issue.expected() != null && issue.expected().endsWith("t"))
                    hint = word + " 以短促的 /t/ 音结尾，不要再加一个音节。";
                return result(level, new TeachingTaskVO("CORRECT", previous.target(), "", previous.attempts() + 1),
                        TeachingOutcomeEnum.READ_RETRY, true, hint, true, true);
            }
            // 【7.3】其余跟读情况：要么评测可信但“低分却没有可信错词”，要么评测本身不可用。
            //      此时不编造发音问题，直接退出原目标回到问答：
            //        READ_UNCERTAIN -> 有评测但证据不足；
            //        NO_EVIDENCE    -> 评测缺失或数值非法。
            //      reliable 固定为 false，表示本轮不产生可信能力证据。
            // 低分但没有可信错词，不编造发音问题；两次后退出原目标。
            return result(level, TeachingTaskVO.question(""), usable ? TeachingOutcomeEnum.READ_UNCERTAIN : TeachingOutcomeEnum.NO_EVIDENCE, true, "", false, true);
        }

        // 【8】普通轮次抽样：既非拒绝/求助，也不是跟读评测。
        //     pureChinese：整句只有中文没有拉丁字母——学员当前无法用英文回应，
        //     此时下一轮必定给跟读示范（比继续中文提问更能推进表达）。
        // 普通纯中文输入下一轮必定跟读；明确拒绝/求助和已有跟读纠音已由上方分支处理。
        boolean pureChinese = cn.adinall.ai.domain.learner.service.level.LearnerLevelTracker.containsChinese(input)
                && !cn.adinall.ai.domain.learner.service.level.LearnerLevelTracker.containsLatin(input);
        // 按水平设定跟读抽样概率：L1 高（0.8，多带读），L2 中（0.5），L3 及以上为 0（靠明确证据驱动）。
        // 使用 draw < rate 的写法配合 [0,1) 边界：draw=0 必中（rate>0），draw=0.8 在 L1 时不中。
        double readingRate = level == EnglishLevelEnum.L1 ? 0.8 : level == EnglishLevelEnum.L2 ? 0.5 : 0;
        // 抽中或纯中文：只下发 READ 动作，具体英文句由模型结合当前输入生成。
        // attempts=0 表示新目标从零开始计数；reliable=false 因为这不是纠音场景。
        if (pureChinese || draw < readingRate) {
            return result(level, new TeachingTaskVO("READ", "", "", 0),
                    TeachingOutcomeEnum.RESPONDED, chinese, "", false, reading);
        }
        // 【9】兜底：抽样未命中，继续保持普通问答节奏（QUESTION）。
        //     注意普通聊天的评测分不会影响任务选择，也不会进入任何纠音分支。
        // 抽样未命中时继续问答；普通聊天评测分不能影响任务选择。
        return result(level, TeachingTaskVO.question(""), TeachingOutcomeEnum.RESPONDED, chinese, "", false, reading);
    }

    /**
     * 所有分支只返回任务状态和可信线索，不返回可直接播报的台词。
     */
    private static TeachingDecisionVO result(EnglishLevelEnum level, TeachingTaskVO task, TeachingOutcomeEnum outcome,
                                             boolean chinese, String correctionHint, boolean reliable, boolean practice) {
        return new TeachingDecisionVO(level, task, outcome, chinese, correctionHint, reliable, practice);
    }

    private static boolean matches(String text, String... hints) {
        for (String hint : hints) if (text.contains(hint)) return true;
        return false;
    }

    private static String shorten(String target) {
        if (target == null || target.isBlank()) return "";
        var words = target.trim().split("\\s+");
        return String.join(" ", java.util.Arrays.copyOf(words, Math.min(words.length, 3)));
    }

}

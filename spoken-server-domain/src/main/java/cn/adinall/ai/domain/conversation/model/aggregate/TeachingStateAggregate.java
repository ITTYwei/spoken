package cn.adinall.ai.domain.conversation.model.aggregate;

import cn.adinall.ai.domain.conversation.model.valobj.TeachingTaskVO;
import cn.adinall.ai.domain.learner.model.valobj.EnglishLevelEnum;
import java.time.Instant;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 功能：保存会话内冻结等级及上一项有效教学任务。
 * <p>体系架构 / 运行过程：</p>
 * <pre>initialize -> propose reply -> commit after media drain -> next input
 * interrupt -> clear pending task (no assumed unheard question)</pre>
 * <p>案例：生成了问题但中途被打断 -> pending 清空，不认定用户拒绝回答。</p>
 * <p>设计要点：</p>
 * <ul><li>等级在本会话内不升级。</li><li>方法同步；旧轮次提交还需要外层 replyId 校验。</li></ul>
 */
public final class TeachingStateAggregate {
    /** 用户主动选择的情境跟读状态，只存于当前业务会话，不从模型历史推断。 */
    private boolean guidedReading;
    /** 已完整播出的句组索引；重复播放同句不前进，未播完的新句也不前进。 */
    private int readingIndex;
    /** 单调快照版本，防止手机用迟到 HTTP 响应覆盖新 SSE 状态。 */
    private long readingVersion;
    /** 首次脚本示范的回复标识；空表示没有等待完成的首次示范。 */
    private String readingOpeningId;

    /** 显式开关具有幂等性；变更清空旧任务，防止普通聊天字幕误成为题面。 */
    public synchronized boolean guidedReading(boolean enabled) {
        if (guidedReading == enabled) return false;
        guidedReading = enabled;
        readingIndex = 0;
        readingOpeningId = null;
        pending = TeachingTaskVO.response();
        readingVersion++;
        return true;
    }
    public synchronized boolean guidedReading() { return guidedReading; }
    public synchronized int readingIndex() { return readingIndex; }

    /** 首句下发前登记标识，只有匹配且完整说完的回调才提交英文目标。 */
    public synchronized void readingOpening(String replyId) { readingOpeningId = replyId; }

    /** 接收真实媒体完成事件；旧回复、被打断示范及未说出的目标均不能产生任务。 */
    public synchronized boolean completeReadingOpening(String replyId, String text) {
        if (!guidedReading || readingOpeningId == null || !readingOpeningId.equals(replyId)) return false;
        var sentence = cn.adinall.ai.domain.conversation.service.reply.GuidedReadingPolicy.sentence(level, readingIndex);
        var task = new TeachingTaskVO(cn.adinall.ai.domain.conversation.model.valobj.TeachingActionEnum.READ.code(), sentence.english(), "", 0);
        var actual = cn.adinall.ai.domain.conversation.service.reply.SpokenTaskResolver.resolve(task, text);
        readingOpeningId = null;
        if (!actual.reading()) return false;
        commit(actual);
        return true;
    }

    /** 已播任务快照；尚无任务时不把首次待播文本或整段讲解暴露成目标。 */
    public synchronized cn.adinall.ai.domain.conversation.model.valobj.ReadingModeVO readingMode() {
        return new cn.adinall.ai.domain.conversation.model.valobj.ReadingModeVO(guidedReading,
                guidedReading && pending.reading() ? pending.target() : "", pending.reading() ? pending.attempts() : 0,
                cn.adinall.ai.domain.conversation.service.reply.GuidedReadingPolicy.MAX_CORRECTIONS, readingVersion);
    }

    /**
     * 所有播报完成路径统一提交任务；情境模式次数由已冻结的服务端计划决定。
     * 不再经过普通聊天的两次建议计数器，避免三次纠音被重置或重复回调多加一次。
     */
    public synchronized void commitSpoken(cn.adinall.ai.domain.conversation.model.valobj.TeachingDecisionVO decision, String text) {
        var actual = cn.adinall.ai.domain.conversation.service.reply.SpokenTaskResolver.resolve(decision.nextTask(), text);
        if (guidedReading && decision.nextTask().reading() && actual.reading()
                && actual.target().equals(decision.nextTask().target())) {
            var current = cn.adinall.ai.domain.conversation.service.reply.GuidedReadingPolicy.sentence(level, readingIndex);
            var next = cn.adinall.ai.domain.conversation.service.reply.GuidedReadingPolicy.sentence(level, readingIndex + 1);
            if (!actual.target().equals(current.english()) && actual.target().equals(next.english())) readingIndex++;
            commit(decision.nextTask());
        } else commit(cn.adinall.ai.domain.conversation.service.reply.SpokenTaskResolver.resolve(decision.nextTask(), decision.previousTask(), text));
    }
    /** 仅提取明确学习诉求作为引用数据，不借此决定模型动作或学习等级。 */
    private static final Pattern LEARNING_REQUEST = Pattern.compile(
            "(?s).*(我(?:现在|今天)?想(?:学|练)|我(?:现在|今天)?要学|教我|i (?:want|would like) to (?:learn|study|practice)|i'd like to (?:learn|study|practice)).*");
    /** 当前会话的学习请求原话；不是长期偏好，不跨会话共享。为空表示没有固定学习目标。 */
    private String learningRequest = "";
    /** 最后一次检查请求的输入时间，防止旧异步轮次覆盖新的学习要求。 */
    private Instant learningRequestAt = Instant.MIN;

    /** 会话期间稳定的能力等级。 */
    private EnglishLevelEnum level;
    /** 上一轮完整播报后等待用户完成的任务。 */
    private TeachingTaskVO pending = TeachingTaskVO.question("");
    public synchronized EnglishLevelEnum initialize(EnglishLevelEnum initial) {
        if (level == null) level = initial;
        return level;
    }
    /**
     * 保存明确学习请求的完整原话供下一轮引用；实际教学意图仍交给模型解释。
     * 跟读/角色素材不保存；明确改学另一内容时新原话替代旧原话，明确结束学习时清空。
     * 超长原话显式拒绝，不截成可能失真的半句；调用方应在组装预算前执行。
     */
    public synchronized void captureLearningRequest(String text, boolean practiceInput, Instant inputAt) {
        if (practiceInput || text == null || inputAt.isBefore(learningRequestAt)) return;
        learningRequestAt = inputAt;
        String value = text.trim().toLowerCase(Locale.ROOT);
        if (value.matches("(?s).*[\"“”‘’].*")) return;
        if (LEARNING_REQUEST.matcher(value).matches()) {
            if (text.length() > 2048) throw new IllegalArgumentException("learning_request_too_large");
            learningRequest = text.trim();
        } else if (value.matches("(?s)^(?:我|今天|现在|先)?(?:不想学|不学了|先不学|不练了|换个话题|随便聊|自由聊).*")) {
            learningRequest = "";
        }
    }
    /** 返回当前学习请求原话，调用方放 USER 必需段，不提升为 SYSTEM 指令。 */
    public synchronized String learningRequest() { return learningRequest; }

    public synchronized TeachingTaskVO pending() { return pending; }
    public synchronized EnglishLevelEnum level() { return level; }
    public synchronized void commit(TeachingTaskVO task) {
        if (!java.util.Objects.equals(pending, task)) readingVersion++;
        pending = task;
    }
    /** 不能认为被打断回复中的新任务已经被用户听到。 */
    public synchronized void interrupt() { readingOpeningId = null; commit(TeachingTaskVO.question("")); }
}

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
    public synchronized void commit(TeachingTaskVO task) { pending = task; }
    /** 不能认为被打断回复中的新任务已经被用户听到。 */
    public synchronized void interrupt() { pending = TeachingTaskVO.question(""); }
}

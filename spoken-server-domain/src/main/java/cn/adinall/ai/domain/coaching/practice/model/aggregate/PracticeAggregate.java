package cn.adinall.ai.domain.coaching.practice.model.aggregate;

import cn.adinall.ai.domain.coaching.practice.model.valobj.PracticeStateVO;
import cn.adinall.ai.types.exception.AppException;
import java.util.HashMap;
import java.util.Map;

/**
 * 会话内练习聚合：管理模式、版本、成功动作去重及继续询问的生效边界。
 * Case 持有对话会话锁后调用本聚合，避免按钮与工具同时操作。聚合自身同步保证快照一致。
 * 原题编号与标题由 scenario 校验后传入，不在领域中读取其他领域仓储。
 * 例：重复提交同一操作只返回当前状态；旧版本新操作被拒绝；被打断询问不能让“好”触发继续。
 */
public final class PracticeAggregate {
    /** 当前不可变快照；初始为自由模式，最近课仅供开场推荐。 */
    private PracticeStateVO state;
    /** 会话期间成功动作的参数指纹；最多 256 项，满后明确拒绝而非驱逐后重复执行。 */
    private final Map<String, String> completed = new HashMap<>();
    /** 已提交但尚未完整播出的询问回复编号，空表示无询问。 */
    private String invitationReply;
    /** 询问提交时的版本，切换后旧媒体完成事件不能激活任务。 */
    private long invitationVersion;

    /** 创建 {@code PracticeAggregate}，初始化其依赖与状态。 */
    public PracticeAggregate(String book, String lesson, String title) {
        state = new PracticeStateVO("FREE", null, null, null, 0, false, book, lesson, title);
    }
    /** 只读快照；调用者不得据此假定旧版本仍可执行。 */
    public synchronized PracticeStateVO snapshot() { return state; }
    /** 在任何副作用前检查幂等和版本；同操作不同参数不能被视作成功重试。 */
    public synchronized boolean check(String operation, String fingerprint, long expected) {
        if (operation == null || !operation.matches("[A-Za-z0-9:_-]{1,100}"))
            throw new AppException("invalid_request", "操作标识无效");
        if (completed.containsKey(operation)) {
            if (!completed.get(operation).equals(fingerprint)) throw new AppException("practice_conflict", "操作标识已用于其他选择");
            return false;
        }
        if (state.version() != expected) throw new AppException("practice_conflict", "练习状态已变化，请重新选择");
        if (completed.size() >= 256) throw new AppException("practice_conflict", "本次会话切换次数已达上限，请重新开始");
        return true;
    }
    /** 持久化和取消成功后提交；失败动作不进入成功去重表，调用者不能盲目重试。 */
    public synchronized PracticeStateVO change(String mode, String book, String lesson, String title,
                                               String operation, String fingerprint) {
        boolean course = "COURSE".equals(mode);
        invitationReply = null;
        state = new PracticeStateVO(mode, book, lesson, title, state.version() + 1, false,
                course ? book : state.recentTextbookId(), course ? lesson : state.recentLessonId(),
                course ? title : state.recentLessonTitle());
        completed.put(operation, fingerprint);
        return state;
    }
    /** 登记待播询问，不在此时接受模糊肯定；只绑定当前真实最近课程。 */
    public synchronized void invite(String reply) {
        if (state.recentLessonId() != null && "FREE".equals(state.mode())) {
            invitationReply = reply; invitationVersion = state.version();
        }
    }
    /** 媒体完整结束回执才激活待选择任务；旧编号或旧版本没有副作用。 */
    public synchronized boolean heard(String reply) {
        if (reply == null || !reply.equals(invitationReply) || invitationVersion != state.version()) return false;
        state = new PracticeStateVO(state.mode(), state.textbookId(), state.lessonId(), state.lessonTitle(), state.version(),
                true, state.recentTextbookId(), state.recentLessonId(), state.recentLessonTitle());
        invitationReply = null;
        return true;
    }
    /** 打断、取消或新用户回合消耗选择窗口，不假定询问已听完。 */
    public synchronized void dismissInvitation() {
        invitationReply = null;
        state = new PracticeStateVO(state.mode(), state.textbookId(), state.lessonId(), state.lessonTitle(), state.version(),
                false, state.recentTextbookId(), state.recentLessonId(), state.recentLessonTitle());
    }
}

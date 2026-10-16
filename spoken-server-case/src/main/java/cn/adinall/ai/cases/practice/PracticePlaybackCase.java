package cn.adinall.ai.cases.practice;

import cn.adinall.ai.domain.coaching.teaching.service.TeachingService;
import cn.adinall.ai.domain.coaching.practice.model.aggregate.PracticeAggregate;
import cn.adinall.ai.domain.coaching.practice.model.valobj.PracticeStateVO;

import cn.adinall.ai.cases.conversation.runtime.IReplyCompletionPort;
import cn.adinall.ai.domain.coaching.practice.service.PracticeService;
import cn.adinall.ai.domain.coaching.conversation.adapter.repository.IConversationSessionRepository;
import cn.adinall.ai.domain.coaching.conversation.adapter.port.IConversationEventBusPort;
import cn.adinall.ai.domain.coaching.conversation.model.valobj.*;
import cn.adinall.ai.domain.coaching.teaching.model.valobj.TeachingTaskVO;
import cn.adinall.ai.domain.coaching.teaching.service.reply.SpokenTaskResolver;
import org.springframework.stereotype.Component;

/**
 * <b>功能</b>：根据实际媒体完成回执提交练习选择窗口及首题任务。
 * <p><b>体系架构 / 运行过程</b></p>
 * <pre>
 * media done -> match replyId + practice version -> publish practice.changed
 *            -> matching first question -> commit teaching task
 * media close -> release practice runtime
 * </pre>
 * <p><b>案例</b>：s-1:practice:2 实际播出问题 -> 该问题才进入待回答状态；旧版本回执不能激活任务。</p>
 * <b>设计要点</b>
 * <ul><li>只依赖领域状态和事件端口，不反向调用会话用例或练习用例。</li>
 * <li>解除媒体运行流程与 PracticeCase 的循环依赖。</li>
 * <li>文本生成结束不能替代媒体播放完成。</li></ul>
 */
@Component
public class PracticePlaybackCase implements IReplyCompletionPort {
    /** 教学状态由独立子域持有，跨域状态变更在业务会话锁内协调。 */
    private final TeachingService teachingStates;

    private final PracticeService practice;
    private final cn.adinall.ai.domain.learning.service.ILearnerLevelService levels;
    private final IConversationEventBusPort events;
    private final IConversationSessionRepository sessions;
    /** 创建 {@code PracticePlaybackCase}，初始化其依赖与状态。 */
    public PracticePlaybackCase(PracticeService practice, IConversationEventBusPort events,
            IConversationSessionRepository sessions, cn.adinall.ai.domain.learning.service.ILearnerLevelService levels, TeachingService teachingStates) {
        this.teachingStates = teachingStates;
        this.levels=levels; this.practice=practice; this.events=events; this.sessions=sessions;
    }
    /** 在回复实际播完后提交课程播放状态并发布变更事件。 */
    @Override public void completed(String sessionId, String replyId, String text) {
        if (text == null || text.isBlank()) return;
        PracticeAggregate practiceState=practice.state(sessionId);
        if (practiceState.heard(replyId))
            events.publish(sessionId, ConversationEventVO.of(EventTypeEnum.PRACTICE_CHANGED, practiceState.snapshot()));
        PracticeStateVO state=practiceState.snapshot();
        if ((sessionId+":practice:"+state.version()).equals(replyId)) {
            sessions.findById(sessionId).ifPresent(session ->
                    teachingStates.state(session.getSessionId()).commit(TeachingTaskVO.question(SpokenTaskResolver.lastQuestion(text))));
        }
    }
    /** 清理已关闭会话关联的课程与学习档位运行态。 */
    @Override public void closed(String sessionId) { practice.close(sessionId); levels.forgetSession(sessionId); }
}

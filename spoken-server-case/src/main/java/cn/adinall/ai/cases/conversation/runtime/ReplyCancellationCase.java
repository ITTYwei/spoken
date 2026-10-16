package cn.adinall.ai.cases.conversation.runtime;

import cn.adinall.ai.domain.coaching.teaching.service.TeachingService;
import cn.adinall.ai.domain.coaching.conversation.model.aggregate.ConversationSessionAggregate;

import cn.adinall.ai.domain.coaching.practice.service.PracticeService;
import cn.adinall.ai.domain.coaching.conversation.adapter.port.IConversationEventBusPort;
import cn.adinall.ai.domain.coaching.conversation.model.valobj.*;
import org.springframework.stereotype.Service;

/** 对话、课程与跟读用例共享的回复取消入口。 */
@Service
public class ReplyCancellationCase {
    /** 教学状态由独立子域持有，跨域状态变更在业务会话锁内协调。 */
    private final TeachingService teachingStates;

    private final IConversationService conversations;
    private final ConversationRuntime runtime;
    private final PracticeService practice;
    private final IConversationEventBusPort events;
    /** 创建 {@code ReplyCancellationCase}，初始化其依赖与状态。 */
    public ReplyCancellationCase(IConversationService conversations, PracticeService practice, IConversationEventBusPort events, ConversationRuntime runtime, TeachingService teachingStates) {
        this.teachingStates = teachingStates;
        this.runtime=runtime;
        this.conversations = conversations; this.practice = practice; this.events = events;
    }
    /** 打断当前回复，取消生成和播放，并清除未播出的待提交状态。 */
    public void interrupt(String id) {
        ConversationSessionAggregate session = conversations.requireSession(id);
        synchronized (session) {
            // 与播放完成回执共用会话锁，先撤销教学计划，再取消异步生产者。
            teachingStates.interrupt(id);
            runtime.cancelReply(id);
            practice.state(id).dismissInvitation();
            conversations.interrupt(id);
            events.publish(id, ConversationEventVO.of(EventTypeEnum.READING_CHANGED,
                    teachingStates.state(id).readingMode()));
        }
    }
}

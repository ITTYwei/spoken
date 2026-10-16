package cn.adinall.ai.cases.teaching;

import cn.adinall.ai.cases.teaching.TeachingPreparation;
import cn.adinall.ai.cases.security.SessionAccess;
import cn.adinall.ai.cases.conversation.runtime.ReplyCancellationCase;
import cn.adinall.ai.cases.conversation.IConversationCase;
import cn.adinall.ai.domain.coaching.conversation.adapter.port.IConversationEventBusPort;
import cn.adinall.ai.domain.coaching.conversation.model.aggregate.ConversationSessionAggregate;
import cn.adinall.ai.domain.coaching.conversation.model.valobj.*;
import cn.adinall.ai.domain.coaching.teaching.model.valobj.ReadingModeVO;
import cn.adinall.ai.cases.conversation.runtime.IConversationService;
import cn.adinall.ai.types.exception.AppException;
import org.springframework.stereotype.Service;

/**
 * 跟读按钮用例：校验账号归属、取消旧回复、切换领域状态并请求模型生成首次情境示范。
 * 首次及后续示范均沿现有 Agent 装配链路生成，不伪造用户发言或另建模型客户端。
 * 失败关闭跟读并发布真实状态；按钮重复打开不会重复示范，画面连接保持不变。
 */
@Service
public class GuidedReadingCase {
    /** 当前 LLM 归属配置；旧上游一体化模式没有本地跟读反馈链路，不能假装可用。 */
    private final cn.adinall.ai.domain.shared.adapter.port.IAppConfigPort config;
    /** 会话入口，提供现有会话状态与媒体连接。 */
    private final IConversationService conversations;
    /** 复用现有取消能力，包含识别/评测中的迟到轮次。 */
    private final IConversationCase conversationCase;
    private final ReplyCancellationCase cancellation;
    private final SessionAccess access;
    /** 用现有教学编排获取可信档位，不从手机指定难度。 */
    private final TeachingPreparation teaching;
    /** 状态事件与播报分离，事件正文不交给 TTS。 */
    private final IConversationEventBusPort events;

    /** 创建 {@code GuidedReadingCase}，初始化其依赖与状态。 */
    public GuidedReadingCase(IConversationService conversations, IConversationCase conversationCase,
                             TeachingPreparation teaching, IConversationEventBusPort events, cn.adinall.ai.domain.shared.adapter.port.IAppConfigPort config, ReplyCancellationCase cancellation, SessionAccess access) {
        this.cancellation=cancellation; this.access=access;
        this.config = config;
        this.conversations = conversations; this.conversationCase = conversationCase;
        this.teaching = teaching; this.events = events;
    }

    /** 查当前账号自己的活动会话；与现有课程入口采用相同主体校验。 */
    private ConversationSessionAggregate owned(String id) {
        ConversationSessionAggregate session = conversations.requireSession(id);
        access.requireOwner(id);
        session.requireOpen();
        return session;
    }

    /** 只读查询可恢复错过的热流状态，不产生语音或推进练习。 */
    public ReadingModeVO get(String id) { return teaching.state(owned(id)).readingMode(); }

    /**
     * 显式设置模式，锁内防止连点重复播报。目标在媒体完成后才生效，响应可暂时为空目标。
     * 不以请求成功声称用户已听到示范；后续 reading.changed 事件携带真正已播出的目标。
     */
    public ReadingModeVO change(String id, boolean enabled) {
        ConversationSessionAggregate session = owned(id);
        synchronized (session) {
            session.requireOpen();
            if (teaching.state(session).guidedReading() == enabled) return teaching.state(session).readingMode();
            if (enabled && config.llmOwner() != LlmOwnerEnum.AGENT)
                throw new AppException("invalid_request", "当前服务未启用教练评测链路，暂不能开启情境跟读");
            cancellation.interrupt(id);
            teaching.level(session);
            teaching.state(session).guidedReading(enabled);
            publish(session);
            if (enabled) {
                try {
                    // 首句沿现有 Agent/媒体轮次链路生成，不写固定句子，也不伪造用户聊天回合。
                    conversationCase.startGuidedReading(id);
                } catch (RuntimeException failed) {
                    teaching.state(session).guidedReading(false);
                    publish(session);
                    throw failed;
                }
            }
            return teaching.state(session).readingMode();
        }
    }

    /** 发布会话当前生效的跟读状态。 */
    private void publish(ConversationSessionAggregate session) {
        events.publish(session.getSessionId(), ConversationEventVO.of(EventTypeEnum.READING_CHANGED, teaching.state(session).readingMode()));
    }
}

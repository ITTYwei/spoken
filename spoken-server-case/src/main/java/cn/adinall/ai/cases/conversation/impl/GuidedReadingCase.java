package cn.adinall.ai.cases.conversation.impl;

import cn.adinall.ai.api.dto.*;
import cn.adinall.ai.cases.conversation.IConversationCase;
import cn.adinall.ai.domain.account.support.AccountContext;
import cn.adinall.ai.domain.conversation.adapter.port.IConversationEventBusPort;
import cn.adinall.ai.domain.conversation.model.aggregate.ConversationSessionAggregate;
import cn.adinall.ai.domain.conversation.model.entity.SayCommandEntity;
import cn.adinall.ai.domain.conversation.model.valobj.*;
import cn.adinall.ai.domain.conversation.service.IConversationService;
import cn.adinall.ai.domain.conversation.service.reply.GuidedReadingPolicy;
import cn.adinall.ai.types.exception.AppException;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

/**
 * 跟读按钮用例：校验账号归属、取消旧回复、切换领域状态并播出首次情境示范。
 * 首次示范走既有脚本轨，不伪造用户发言或调用新模型；后续反馈仍沿现有 Agent 装配链路。
 * 失败关闭跟读并发布真实状态；按钮重复打开不会重复示范，画面连接保持不变。
 */
@Service
public class GuidedReadingCase {
    /** 当前 LLM 归属配置；旧上游一体化模式没有本地跟读反馈链路，不能假装可用。 */
    private final cn.adinall.ai.domain.shared.adapter.port.IAppConfigPort config;
    /** 会话入口，提供现有媒体连接和脚本播报。 */
    private final IConversationService conversations;
    /** 复用现有取消能力，包含识别/评测中的迟到轮次。 */
    private final IConversationCase conversationCase;
    /** 用现有教学编排获取可信档位，不从手机指定难度。 */
    private final TeachingPreparation teaching;
    /** 状态事件与播报分离，事件正文不交给 TTS。 */
    private final IConversationEventBusPort events;

    public GuidedReadingCase(IConversationService conversations, @Lazy IConversationCase conversationCase,
                             TeachingPreparation teaching, IConversationEventBusPort events, cn.adinall.ai.domain.shared.adapter.port.IAppConfigPort config) {
        this.config = config;
        this.conversations = conversations; this.conversationCase = conversationCase;
        this.teaching = teaching; this.events = events;
    }

    /** 查当前账号自己的活动会话；与现有课程入口采用相同主体校验。 */
    private ConversationSessionAggregate owned(String id) {
        var session = conversations.requireSession(id);
        if (!session.teachingSubject().equals("user:" + AccountContext.require().id()))
            throw new AppException("session_not_found", "会话不存在或已结束");
        session.requireOpen();
        return session;
    }

    /** 只读查询可恢复错过的热流状态，不产生语音或推进练习。 */
    public ReadingModeResponseDTO get(String id) { return dto(owned(id).teaching().readingMode()); }

    /**
     * 显式设置模式，锁内防止连点重复播报。目标在媒体完成后才生效，响应可暂时为空目标。
     * 不以请求成功声称用户已听到示范；后续 reading.changed 事件携带真正已播出的目标。
     */
    public ReadingModeResponseDTO change(String id, boolean enabled) {
        var session = owned(id);
        synchronized (session) {
            session.requireOpen();
            if (session.teaching().guidedReading() == enabled) return dto(session.teaching().readingMode());
            if (enabled && config.llmOwner() != LlmOwnerEnum.AGENT)
                throw new AppException("invalid_request", "当前服务未启用教练评测链路，暂不能开启情境跟读");
            conversationCase.interrupt(id);
            teaching.level(session);
            session.teaching().guidedReading(enabled);
            publish(session);
            if (enabled) {
                String reply = id + ":reading:" + session.teaching().readingMode().version();
                session.teaching().readingOpening(reply);
                try {
                    conversations.say(id, new SayCommandEntity(GuidedReadingPolicy.sentence(session.teaching().level(), 0).demonstration(), session.getVoice(), reply));
                } catch (RuntimeException failed) {
                    session.teaching().guidedReading(false);
                    publish(session);
                    throw failed;
                }
            }
            return dto(session.teaching().readingMode());
        }
    }

    private void publish(ConversationSessionAggregate session) {
        events.publish(session.getSessionId(), ConversationEventVO.of(EventTypeEnum.READING_CHANGED, session.teaching().readingMode()));
    }
    private static ReadingModeResponseDTO dto(ReadingModeVO state) {
        return new ReadingModeResponseDTO(state.enabled(), state.target(), state.correctionRetries(), state.maxCorrections(), state.version());
    }
}

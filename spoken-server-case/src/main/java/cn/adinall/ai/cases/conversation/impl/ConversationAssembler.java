package cn.adinall.ai.cases.conversation.impl;

import cn.adinall.ai.cases.conversation.model.SessionResult;
import cn.adinall.ai.domain.coaching.conversation.model.aggregate.ConversationSessionAggregate;
import cn.adinall.ai.domain.coaching.conversation.model.valobj.SessionStatusEnum;

final class ConversationAssembler {
    /** 禁止实例化，仅通过静态方法使用此工具类。 */
    private ConversationAssembler() { }
    /** 将会话聚合转换为应用结果，不引入 HTTP DTO。 */
    static SessionResult toResponse(ConversationSessionAggregate session) {
        return new SessionResult(session.getSessionId(), session.getStatus().name(), session.getAvatarId(),
                session.getModel(), session.getVoice(), session.getCreatedAt(),
                session.getStatus() == SessionStatusEnum.CLOSED ? session.getLastEventAt() : null, session.getErrorMessage());
    }
}

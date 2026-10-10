package cn.adinall.ai.domain.business.conversation.adapter.repository;

import cn.adinall.ai.domain.business.conversation.model.aggregate.ConversationTurnAggregate;

import java.util.List;
import java.util.Optional;

/** 回合仓储。回合必须先落库拿到自增 id，App 的跟读评测拿这个 id 当键。 */
public interface IConversationTurnRepository {

    /** 落一条新回合，返回回填了自增主键的聚合。 */
    ConversationTurnAggregate append(ConversationTurnAggregate turn);

    /** 回填数字人回复（整段文本 + 延迟）。 */
    void updateAssistantText(ConversationTurnAggregate turn);

    Optional<ConversationTurnAggregate> findById(Long turnId);

    List<ConversationTurnAggregate> listBySessionId(String sessionId);

    /**
     * 取该会话 seq 最大的那条回合。
     * 用途一：算出下一个 seq；用途二：找到刚建的那条用户回合，把数字人的回复回填上去。
     */
    Optional<ConversationTurnAggregate> findLastBySessionId(String sessionId);
}
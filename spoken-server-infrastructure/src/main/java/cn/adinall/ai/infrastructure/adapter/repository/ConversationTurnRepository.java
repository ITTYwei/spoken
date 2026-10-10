package cn.adinall.ai.infrastructure.adapter.repository;

import cn.adinall.ai.domain.business.conversation.adapter.repository.IConversationTurnRepository;
import cn.adinall.ai.domain.business.conversation.model.aggregate.ConversationTurnAggregate;
import cn.adinall.ai.infrastructure.dao.IConversationTurnDao;
import cn.adinall.ai.infrastructure.dao.po.ConversationTurnPO;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * 回合仓储实现：领域聚合 <-> conversation_turn 表。
 *
 * 关键点只有一个：append 必须把数据库生成的自增主键回填进聚合 ——
 * App 的跟读评测就是拿这个 id 当键（assessment.turn_id），拿不到 id 这条链路就断了。
 */
@Repository
public class ConversationTurnRepository implements IConversationTurnRepository {

    private final IConversationTurnDao turnDao;

    public ConversationTurnRepository(IConversationTurnDao turnDao) {
        this.turnDao = turnDao;
    }

    /**
     * 落一条新回合并回填自增主键。
     * 流程：聚合转 PO -> insert（MyBatis 会把生成的 key 写回 PO）-> 把 id 交回聚合。
     */
    @Override
    @Transactional
    public ConversationTurnAggregate append(ConversationTurnAggregate turn) {
        ConversationTurnPO po = new ConversationTurnPO();
        po.setSessionId(turn.getSessionId());
        po.setSeq(turn.getSeq());
        po.setUserText(turn.getUserText());
        po.setAssistantText(turn.getAssistantText());
        po.setAudioPath(turn.getAudioPath());
        po.setLatencyMs(turn.getLatencyMs());
        po.setCreatedAt(turn.getCreatedAt());
        turnDao.insert(po);
        turn.assignId(po.getId());
        return turn;
    }

    /** 把数字人的回复（整段文本 + 延迟）落到已有回合上。 */
    @Override
    @Transactional
    public void updateAssistantText(ConversationTurnAggregate turn) {
        ConversationTurnPO po = new ConversationTurnPO();
        po.setId(turn.getId());
        po.setAssistantText(turn.getAssistantText());
        po.setLatencyMs(turn.getLatencyMs());
        turnDao.updateAssistant(po);
    }

    @Override
    public Optional<ConversationTurnAggregate> findById(Long turnId) {
        return Optional.ofNullable(turnDao.selectById(turnId)).map(ConversationTurnRepository::toAggregate);
    }

    @Override
    public List<ConversationTurnAggregate> listBySessionId(String sessionId) {
        return turnDao.selectBySessionIdOrderBySeqAsc(sessionId).stream()
                .map(ConversationTurnRepository::toAggregate)
                .toList();
    }

    @Override
    public Optional<ConversationTurnAggregate> findLastBySessionId(String sessionId) {
        return Optional.ofNullable(turnDao.selectLastBySessionId(sessionId))
                .map(ConversationTurnRepository::toAggregate);
    }

    /** PO -> 聚合（用 restore 而不是 create：id 和 created_at 必须是库里的原值）。 */
    private static ConversationTurnAggregate toAggregate(ConversationTurnPO po) {
        return ConversationTurnAggregate.restore(po.getId(), po.getSessionId(),
                po.getSeq() == null ? 0 : po.getSeq(), po.getUserText(), po.getAssistantText(),
                po.getAudioPath(), po.getLatencyMs(),
                po.getCreatedAt() == null ? System.currentTimeMillis() : po.getCreatedAt());
    }
}
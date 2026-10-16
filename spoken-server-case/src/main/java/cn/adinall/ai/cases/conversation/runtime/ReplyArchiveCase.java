package cn.adinall.ai.cases.conversation.runtime;

import cn.adinall.ai.domain.coaching.teaching.service.TeachingService;
import cn.adinall.ai.domain.coaching.conversation.model.aggregate.ConversationTurnAggregate;
import cn.adinall.ai.domain.coaching.teaching.model.valobj.TeachingPlaybackVO;

import cn.adinall.ai.domain.coaching.conversation.adapter.repository.*;
import cn.adinall.ai.domain.coaching.teaching.adapter.repository.ITeachingReplyRepository;
import cn.adinall.ai.domain.coaching.conversation.model.aggregate.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Optional;

/** 在更新运行状态之前，以同一事务提交完成凭据、对话文本和教学文本。 */
@Service
public class ReplyArchiveCase {
    /** 教学状态由独立子域持有，跨域状态变更在业务会话锁内协调。 */
    private final TeachingService teachingStates;

    private final IConversationTurnRepository turns;
    private final ITeachingReplyRepository teaching;
    private final IReplyReceiptRepository receipts;
    /** 创建 {@code ReplyArchiveCase}，初始化其依赖与状态。 */
    public ReplyArchiveCase(IConversationTurnRepository turns, ITeachingReplyRepository teaching, IReplyReceiptRepository receipts, TeachingService teachingStates) {
        this.teachingStates = teachingStates;
        this.turns = turns; this.teaching = teaching; this.receipts = receipts;
    }
    /** 在同一事务中登记完成凭据、对话文本与教学文本；失败时整体回滚并允许重试。 */
    @Transactional
    public void complete(ConversationSessionAggregate session, String text, Long latency) {
        if (!receipts.claim(session.getSessionId(), session.replyReceiptId())) return;
        if (text == null || text.isBlank()) return;
        Long turnId = session.replyTurnId();
        if (!session.replyTurnBound()) {
            TeachingPlaybackVO pending = teachingStates.state(session.getSessionId()).pendingPlayback();
            turnId = pending != null ? pending.turnId() : turns.findLastBySessionId(session.getSessionId())
                    .filter(t -> t.getUserText() != null && t.getAssistantText() == null)
                    .map(ConversationTurnAggregate::getId).orElse(null);
        }
        Optional<ConversationTurnAggregate> existing = turnId == null ? Optional.<ConversationTurnAggregate>empty() : turns.findById(turnId);
        if (existing.isPresent()) {
            ConversationTurnAggregate source = existing.get();
            ConversationTurnAggregate completed = ConversationTurnAggregate.restore(source.getId(), source.getSessionId(), source.getSeq(),
                    source.getUserText(), text, source.getAudioPath(), latency == null ? source.getLatencyMs() : latency, source.getCreatedAt());
            turns.updateAssistantText(completed);
        } else {
            int seq = turns.findLastBySessionId(session.getSessionId()).map(t -> t.getSeq() + 1).orElse(0);
            ConversationTurnAggregate completed = ConversationTurnAggregate.create(session.getSessionId(), seq, null);
            completed.complete(text, latency);
            turns.append(completed);
        }
        TeachingPlaybackVO pending = teachingStates.state(session.getSessionId()).pendingPlayback();
        if (pending != null && pending.turnId() != null && session.acceptsReply(pending.replyId()))
            teaching.complete(pending.turnId(), pending.replyId(), text);
    }
}

package cn.adinall.ai.infrastructure.adapter.repository;

import cn.adinall.ai.domain.coaching.conversation.adapter.repository.IReplyReceiptRepository;
import cn.adinall.ai.infrastructure.dao.IReplyReceiptDao;
import org.springframework.stereotype.Repository;

@Repository
public class ReplyReceiptRepository implements IReplyReceiptRepository {
    private final IReplyReceiptDao dao;
    /** 创建 {@code ReplyReceiptRepository}，初始化其依赖与状态。 */
    public ReplyReceiptRepository(IReplyReceiptDao dao) { this.dao = dao; }
    /** 尝试登记会话回复的完成凭据，首次登记成功才允许归档。 */
    @Override public boolean claim(String sessionId, String replyId) { return dao.claim(sessionId, replyId) == 1; }
}

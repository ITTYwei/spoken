package cn.adinall.ai.domain.coaching.conversation.adapter.repository;

/** 持久化回复去重契约，与回复归档加入同一数据库事务。 */
public interface IReplyReceiptRepository {
    /** 尝试登记会话回复的完成凭据，首次登记成功才允许归档。 */
    boolean claim(String sessionId, String replyId);
}

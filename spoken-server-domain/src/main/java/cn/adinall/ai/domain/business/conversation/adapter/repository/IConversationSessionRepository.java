package cn.adinall.ai.domain.business.conversation.adapter.repository;

import cn.adinall.ai.domain.business.conversation.model.aggregate.ConversationSessionAggregate;

import java.util.Collection;
import java.util.Optional;

/**
 * 会话仓储（本地数据）。
 *
 * 它同时管两份状态，这是有意的：
 * - **内存**：会话的运行态（上游句柄、流式缓冲、最近一次说话的时刻）。进程重启即失效，
 *   这与现状一致 —— 重启后老 sessionId 一律 404，客户端重新建会话；
 * - **数据库**：会话的档案（谁、用什么形象和模型、什么时候开始结束、什么状态），
 *   给以后做「训练历史」用，重启后仍在。
 *
 * 领域只看到「查得到/存得下」，不关心哪一层是内存哪一层是库。
 */
public interface IConversationSessionRepository {

    /** 登记一场新会话：写内存索引，并落一条档案记录。 */
    void save(ConversationSessionAggregate aggregate);

    /** 会话状态变了（READY / CLOSED / ERROR）后同步档案。 */
    void update(ConversationSessionAggregate aggregate);

    /** 按我方 sessionId 取运行中的会话；进程重启或已关闭就取不到。 */
    Optional<ConversationSessionAggregate> findById(String sessionId);

    /** 按设备取它当前那场会话，用于「同设备只留一场」的约束。 */
    Optional<ConversationSessionAggregate> findByDeviceId(String deviceId);

    /** 摘掉这场会话的运行态（关会话后调用）。 */
    Optional<ConversationSessionAggregate> remove(String sessionId);

    /** 当前活着的所有会话，给运维接口 / 空闲回收用。 */
    Collection<ConversationSessionAggregate> findAll();
}
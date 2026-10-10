package cn.adinall.ai.domain.conversation.adapter.repository;

import cn.adinall.ai.domain.conversation.model.aggregate.ConversationSessionAggregate;

import java.util.Collection;
import java.util.Optional;

/**
 * 会话仓储（同一场会话的「内存运行态 + 数据库档案」两份状态）
 * <p>
 * 功能：对领域只暴露「查得到 / 存得下」，至于是内存还是库由实现决定。
 * <pre>
 *   save(ConversationSessionAggregate)     登记新会话：写内存索引 + 落一条档案记录
 *   update(ConversationSessionAggregate)   状态变了（READY / CLOSED / ERROR）后同步档案
 *   findById(String)                       按我方 sessionId 取运行中的会话
 *   findByDeviceId(String)                 按设备取它当前那场会话
 *   remove(String)                         摘掉运行态（关会话后调用）
 *   findAll()                              当前活着的所有会话（运维接口 / 空闲回收用）
 * </pre>
 * 体系架构（两份状态各管什么）：
 * <pre>
 *   ConversationServiceImpl（领域服务）
 *        |
 *        v
 *   IConversationSessionRepository（本接口）
 *        |
 *        +--> 内存索引：聚合对象本体（上游句柄、流式缓冲、最近说话时刻、状态）
 *        |        进程重启即失效 —— 重启后老 sessionId 一律 404，客户端重新建会话
 *        |
 *        +--> 数据库档案：谁、用什么形象和模型、什么时候开始结束、什么状态
 *                 给以后做「训练历史」用，重启后仍在
 * </pre>
 * 案例：
 * <pre>
 *   输入: save(session)，其中 getSessionId()="s-9c1d2f3e"、getProviderSessionId()="ot-7f3a"、
 *         getDeviceId()="dev-001"、getStatus()=QUEUED
 *   处理: 内存索引 put("s-9c1d2f3e", session)，数据库 insert 一条档案
 *   输出: findById("s-9c1d2f3e") 立刻拿得到同一个对象；findByDeviceId("dev-001") 也指向它
 *         之后 remove("s-9c1d2f3e") --> findById 返回 Optional.empty()（档案记录仍留在库里）
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>同时管两份状态是有意的：运行态（上游句柄、流式缓冲）只对活着的进程有意义，
 *       落库反而要额外处理序列化与不一致；档案（谁在什么时候练了什么）才是要长期留的数据。</li>
 *   <li>findById 同时是「这个会话还活着吗」的判据：closeSession 会 remove，
 *       所以进程重启或已关闭后一律查不到 —— 调用方不能把它当永久存储查询。</li>
 *   <li>findByDeviceId 支撑「同设备只留一场」：用例层建会话前先关掉它，
 *       单 GPU 槽才不会被老会话一直占着（老会话不让位，新会话就只能排队）。</li>
 * </ul>
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
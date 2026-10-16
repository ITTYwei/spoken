package cn.adinall.ai.domain.coaching.conversation.adapter.repository;

import cn.adinall.ai.domain.coaching.conversation.model.aggregate.ConversationTurnAggregate;

import java.util.Optional;

/**
 * 回合仓储（一问一答的持久化出口）
 * <p>
 * 功能：回合必须先落库拿到自增 id，App 的跟读评测拿这个 id 当键（assessment.turn_id）。
 * <pre>
 *   append(ConversationTurnAggregate)               落一条新回合，返回回填了自增主键的聚合
 *   updateAssistantText(ConversationTurnAggregate)  回填数字人回复（整段文本 + 延迟）
 *   findById(Long)                                  按回合 id 取
 *   findLastBySessionId(String)                     取 seq 最大的那条（算下一个 seq / 找刚建的回合）
 * </pre>
 * 运行过程（一条回合的两次写）：
 * <pre>
 *   appendUserTurn(sessionId, "I went to the park.")
 *        |
 *        | findLastBySessionId(sessionId) --> seq=1 --> nextSeq=2
 *        | ConversationTurnAggregate.create(sessionId, 2, "I went to the park.")
 *        v
 *   append(turn)
 *        |
 *        | INSERT --> 自增 id=1024 --> turn.assignId(1024L)
 *        v
 *   事件 stt.final 带上 turnId=1024 发给 App
 *
 *   ... assistant.delta / assistant.done ...
 *        |
 *        | findById(replyTurnId) --> 取回回复开始时绑定的 turn
 *        v
 *   turn.complete(整段回复, latencyMs) --> updateAssistantText(turn)
 * </pre>
 * 案例：
 * <pre>
 *   输入: append(ConversationTurnAggregate.create("s-9c1d2f3e", 0, "I went to the park."))
 *   处理: INSERT 后回填自增主键
 *   输出: 同一个聚合对象，getId() = 1024L；此时 getAssistantText() 仍是 null
 *         findLastBySessionId("s-9c1d2f3e") 返回 seq=0 的这条
 *   续: 数字人回完后 updateAssistantText --> 该行 assistant_text / latency_ms 被补上
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>id 必须来自数据库自增而不是内存生成：跟读评分、错题本等下游数据都以外键挂在
 *       turn_id 上，id 一旦对不上，评分就落到别的回合上。</li>
 *   <li>findLastBySessionId 被复用成两种用途（算 nextSeq、找回刚建的用户回合），
 *       所以它必须按 seq 取最大而不是按创建时间 —— 同一秒内连说两句时时间戳可能相同。</li>
 *   <li>没有用户回合时（纯文本驱动数字人）也会 append 一条 userText=null 的回合，
 *       目的是让 assistantText 有个归属，别为了「userText 非空」把整段回复丢掉。</li>
 * </ul>
 */
public interface IConversationTurnRepository {

    /** 落一条新回合，返回回填了自增主键的聚合。 */
    ConversationTurnAggregate append(ConversationTurnAggregate turn);

    /** 回填数字人回复（整段文本 + 延迟）。 */
    void updateAssistantText(ConversationTurnAggregate turn);

    /** 按回合标识查找对话记录。 */
    Optional<ConversationTurnAggregate> findById(Long turnId);

    /**
     * 取该会话 seq 最大的那条回合。
     * 用途一：算出下一个 seq；用途二：找到刚建的那条用户回合，把数字人的回复回填上去。
     */
    Optional<ConversationTurnAggregate> findLastBySessionId(String sessionId);
}
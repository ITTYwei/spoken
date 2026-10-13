package cn.adinall.ai.domain.conversation.adapter.repository;

import cn.adinall.ai.domain.identity.model.valobj.OpeningSubjectVO;
import cn.adinall.ai.domain.conversation.model.valobj.OpeningFactsVO;
import java.time.Instant;

/**
 * 读取当前主体的有效对话事实及明确待聊素材。
 * <p>
 * 功能：读取当前主体的有效对话事实及明确待聊素材。
 * <p>
 * 体系架构 / 运行过程：
 * <pre>
 *   用例 -> 历史仓储接口 -> 数据库历史适配器
 * </pre>
 * 案例：
 * <pre>
 *   输入 -> 输出：匿名 d1 + free -> 只读取 d1 未归属账号的有效历史
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>主体来自身份端口。</li>
 *   <li>不同场景的待聊事实不续接。</li>
 * </ul>
 */
public interface IOpeningHistoryRepository {
    OpeningFactsVO read(OpeningSubjectVO subject, Instant now, String scenarioId);
    /** 只保存领域已确认的用户延后请求，SQL 再确认该回合归属当前会话。 */
    default void remember(String sessionId,Long turn,cn.adinall.ai.domain.conversation.model.valobj.OpeningMaterialVO topic) {}
    /** 用户明确结束、拒绝或撤回待聊话题后，不再用于开场。 */
    default void finishTopic(String sessionId,Long turn) {}
}

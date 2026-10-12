package cn.adinall.ai.infrastructure.adapter.repository;

import cn.adinall.ai.domain.conversation.adapter.repository.IConversationTurnRepository;
import cn.adinall.ai.domain.conversation.model.aggregate.ConversationTurnAggregate;
import cn.adinall.ai.infrastructure.dao.IConversationTurnDao;
import cn.adinall.ai.infrastructure.dao.DatabaseTime;
import cn.adinall.ai.infrastructure.dao.po.ConversationTurnPO;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * 回合仓储实现（IConversationTurnRepository）：领域聚合与 conversation_turn 表互转
 * <p>
 * 功能：一问一答的落库与读取；核心契约是 append 之后聚合必须带上数据库生成的自增 turnId。
 * <pre>
 *   append(turn)                   插一条回合并回填自增主键（@Transactional），返回入参聚合
 *   updateAssistantText(turn)      把数字人回复与延迟补到已有回合上（@Transactional）
 *   findById(turnId)               按自增主键查单条，PO 用 restore 还原成聚合
 *   listBySessionId(sessionId)     按会话查全部回合并按 seq 升序，用于 /turns 恢复整段聊天记录
 *   findLastBySessionId(sessionId) 取 seq 最大的回合：算下一个 seq，或回填刚建的用户回合
 * </pre>
 * 运行过程（用户说完一句话到数字人回复定稿）：
 * <pre>
 *   ConversationServiceImpl.appendUserTurn(sessionId, text)
 *        |
 *        | (1) nextSeq = findLastBySessionId(sessionId).map(t -> t.getSeq() + 1).orElse(0)
 *        |     turn = ConversationTurnAggregate.create(sessionId, nextSeq, text)   // id == null
 *        |
 *        | (2) turnRepository.append(turn)
 *        v
 *   append(turn)
 *        |
 *        +--> 聚合字段抄进 ConversationTurnPO（sessionId/seq/userText/audioPath/latencyMs/createdAt）
 *        +--> turnDao.insert(po)
 *        |        Mapper: useGeneratedKeys="true" keyProperty="id" 把自增主键写回 po
 *        +--> turn.assignId(po.getId())
 *        v
 *   聚合带上 turnId
 *        |
 *        | ConversationServiceImpl 立刻发 stt.final（data 里带 turnId）
 *        |   -> App 的跟读评测拿它当键（assessment.turn_id）
 *        v
 *   数字人回复定稿: turn.complete(assistantText, latencyMs)
 *        |
 *        v
 *   updateAssistantText(turn) --> update conversation_turn
 *        set assistant_text = #{assistantText}, latency_ms = #{latencyMs} where id = #{id}
 * </pre>
 * 案例：
 * <pre>
 *   输入: turn = ConversationTurnAggregate.create("s-3f9c1a77e2b04d15", 3, "I want a coffee")
 *         （创建后 getId() == null、getAssistantText() == null）
 *   处理: append(turn) -> turnDao.insert(po) -> conversation_turn 插入一行
 *         (session_id="s-3f9c1a77e2b04d15", seq=3, user_text="I want a coffee",
 *          assistant_text=null, audio_path=null, latency_ms=null, created_at=1730000000100)
 *   输出: 同一个 turn 对象，getId() 变成数据库生成的 turnId（例如 42）
 *   续: turn.complete("Sure, what would you like?", 1850L)
 *       -> updateAssistantText(turn) -> 该行 assistant_text / latency_ms 被更新
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>append 返回的是入参聚合而不是新建对象：调用方拿到的就是被 assignId 回填过的那个实例，
 *       而 turnId 是 App 跟读评测的唯一键，拿不到 id 这条链路就断了。</li>
 *   <li>id 回填靠 Mapper XML 里的 useGeneratedKeys="true" keyProperty="id"，不是本类手写的；
 *       改 ConversationTurnMapper.xml 时别把这两个属性弄丢。</li>
 *   <li>读回来用 ConversationTurnAggregate.restore(...) 而不是 create(...)：
 *       id 和 created_at 必须是库里的原值，create 会把 id 置空、时间置成当前时刻。</li>
 *   <li>toAggregate 对 seq / created_at 做了 null 兜底（0 / 当前毫秒）：
 *       老数据或手工插入的行缺列时，不至于把聚合搞出 null 拆箱异常。</li>
 *   <li>回合生命周期是「先建后补」：append 时 assistantText 还是 null，
 *       数字人回复定稿后才由 complete + updateAssistantText 补上，所以这个聚合不是不可变对象。</li>
 * </ul>
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
                po.getCreatedAt() == null ? System.currentTimeMillis() : DatabaseTime.toEpochMillis(po.getCreatedAt()));
    }
}

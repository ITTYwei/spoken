package cn.adinall.ai.infrastructure.dao;

import cn.adinall.ai.infrastructure.dao.po.ConversationTurnPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * conversation_turn 表的 MyBatis DAO（一问一答的落库与读取）
 * <p>
 * 功能：声明回合表需要的五个动作，SQL 全部写在 resources/mybatis/mapper/ConversationTurnMapper.xml。
 * <pre>
 *   insert(turnPO)                   插一条回合；useGeneratedKeys 把自增主键回填到 turnPO.id
 *   selectById(id)                   按自增主键查单条
 *   selectBySessionIdOrderBySeqAsc(sessionId)  按会话查全部，seq 升序（/turns 恢复整段聊天记录）
 *   selectLastBySessionId(sessionId) 取 seq 最大的一条（算下一个 seq / 回填数字人回复）
 *   updateAssistant(turnPO)          把 assistant_text 与 latency_ms 写到指定 id 上
 * </pre>
 * 体系架构（谁调它、SQL 在哪）：
 * <pre>
 *   ConversationTurnRepository（唯一调用方）
 *        |
 *        +--> append()               -> insert()                        自增 id 回填
 *        +--> updateAssistantText()  -> updateAssistant()
 *        +--> findById()             -> selectById()
 *        +--> listBySessionId()      -> selectBySessionIdOrderBySeqAsc()
 *        +--> findLastBySessionId()  -> selectLastBySessionId()
 *        |
 *        v
 *   IConversationTurnDao（本接口，@Mapper）
 *        |  由 PersistenceConfiguration 的 @MapperScan 生成代理
 *        v
 *   ConversationTurnMapper.xml（namespace 指向本接口全限定名）
 *        |
 *        v
 *   conversation_turn 表（id 自增，表结构见 Flyway 建表脚本）
 * </pre>
 * 运行过程（insert 的自增主键回填）：
 * <pre>
 *   ConversationTurnRepository.append(turn)
 *        |
 *        | (1) PO 只放 sessionId / seq / userText / audioPath / latencyMs / createdAt
 *        |     （id 留 null，交给数据库生成）
 *        |
 *        | (2) turnDao.insert(po)
 *        v
 *   ConversationTurnMapper.xml
 *        |
 *        +--> insert into conversation_turn (session_id, seq, user_text, assistant_text,
 *        |                                    audio_path, latency_ms, created_at)
 *        |         values (#{sessionId}, #{seq}, #{userText}, #{assistantText},
 *        |                 #{audioPath}, #{latencyMs}, #{createdAt})
 *        +--> useGeneratedKeys="true" keyProperty="id"
 *        v
 *   MyBatis 把生成的 id 写回 po.id --> 仓储再 assignId 给聚合
 * </pre>
 * 案例：
 * <pre>
 *   输入: insert 的 PO = ConversationTurnPO{id=null, sessionId="s-3f9c1a77e2b04d15", seq=3,
 *         userText="I want a coffee", assistantText=null, audioPath=null,
 *         latencyMs=null, createdAt=1730000000100}
 *   处理: Mapper 执行 insert into conversation_turn (...)，数据库分配自增 id=42
 *   输出: 返回受影响行数 1；副作用是 po.getId() 变成 42L，且该表多一行
 *   续: selectLastBySessionId("s-3f9c1a77e2b04d15") 返回的正是这一行
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>插入必须回填自增主键：App 的跟读评测拿 turnId 当键（assessment.turn_id），
 *       拿不到 id 这条链路就断了 —— useGeneratedKeys / keyProperty 写在 XML 里，最容易被改漏。</li>
 *   <li>只声明方法、不写 SQL 注解：SQL 集中在 XML 里，改查询条件不用重编译，也便于直接审 SQL。</li>
 *   <li>两个带参方法都标 @Param：靠它绑定 #{id} / #{sessionId}，去掉会报 Parameter 找不到。</li>
 *   <li>updateAssistant 只改 assistant_text 与 latency_ms，不动 user_text：
 *       回合是「先建后补」的，用户那句话落库后不应再被改写。</li>
 * </ul>
 */
@Mapper
public interface IConversationTurnDao {

    /** 插一条回合，并把数据库生成的自增主键回填到入参对象上。 */
    int insert(ConversationTurnPO turnPO);

    /** 按自增主键查单条回合。 */
    ConversationTurnPO selectById(@Param("id") Long id);

    /** 按会话查全部回合并按 seq 升序返回，用于 /turns 接口恢复整段聊天记录。 */
    List<ConversationTurnPO> selectBySessionIdOrderBySeqAsc(@Param("sessionId") String sessionId);

    /**
     * 取该会话 seq 最大的那条回合。
     * 用途一：算出下一个 seq；用途二：找到刚建的那条用户回合，把数字人的回复回填上去。
     */
    ConversationTurnPO selectLastBySessionId(@Param("sessionId") String sessionId);

    /** 把数字人的回复落到这条回合上（整段文本 + 响应延迟）。 */
    int updateAssistant(ConversationTurnPO turnPO);
}
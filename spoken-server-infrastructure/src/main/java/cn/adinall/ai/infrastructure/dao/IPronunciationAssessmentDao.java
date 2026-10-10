package cn.adinall.ai.infrastructure.dao;

import cn.adinall.ai.infrastructure.dao.po.PronunciationAssessmentPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * pronunciation_assessment 表的 MyBatis DAO（跟读评测记录的落库与读取）
 * <p>
 * 功能：声明评测记录表需要的四个动作，SQL 全部写在 resources/mybatis/mapper/PronunciationAssessmentMapper.xml。
 * <pre>
 *   insert(po)                       插一条评测；useGeneratedKeys 把自增主键回填到 po.id
 *   selectById(id)                   按自增主键查单条
 *   selectBySessionIdDesc(sessionId) 按会话查全部，按创建时间倒序（记录页最近的在前）
 *   selectByTurnId(turnId)           按回合查（App 拿 turnId 回看这一轮跟读的分数）
 * </pre>
 * 体系架构（谁调它、SQL 在哪）：
 * <pre>
 *   PronunciationAssessmentRepository（唯一调用方）
 *        |
 *        +--> save()            -> insert()                      自增 id 回填
 *        +--> findById()        -> selectById()
 *        +--> listBySessionId() -> selectBySessionIdDesc()
 *        +--> listByTurnId()    -> selectByTurnId()
 *        |
 *        v
 *   IPronunciationAssessmentDao（本接口，@Mapper）
 *        |  由 PersistenceConfiguration 的 @MapperScan("cn.adinall.ai.infrastructure.dao") 生成代理
 *        v
 *   PronunciationAssessmentMapper.xml（namespace 指向本接口全限定名）
 *        |
 *        v
 *   pronunciation_assessment 表（id 自增，表结构见 Flyway V3）
 * </pre>
 * 运行过程（insert 的自增主键回填）：
 * <pre>
 *   PronunciationAssessmentRepository.save(assessment)
 *        |
 *        | (1) PO 装配：sessionId / turnId / expectedText / lang / contentType / audioBytes /
 *        |     score / transcribe / 两个错误率 / acousticDistance / feedback /
 *        |     resultJson（result 序列化而来）/ latencyMs / createdAt
 *        |     （id 留 null，交给数据库生成）
 *        |
 *        | (2) assessmentDao.insert(po)
 *        v
 *   PronunciationAssessmentMapper.xml
 *        |
 *        +--> insert into pronunciation_assessment (...) values (...)
 *        +--> useGeneratedKeys="true" keyProperty="id"
 *        v
 *   MyBatis 把生成的 id 写回 po.id --> 仓储再 assignId 给聚合
 * </pre>
 * 案例：
 * <pre>
 *   输入: insert 的 PO = PronunciationAssessmentPO{id=null, sessionId="s-3f9c1a77e2b04d15",
 *         turnId=null, expectedText="Hello, how are you?", lang="en", contentType="audio/wav",
 *         audioBytes=37940, score=86.67, transcribe="HELLO HOW ARE YOU", wordErrorRate=0.0,
 *         phonemeErrorRate=0.3333, acousticDistance=0.0, feedback="🔊 ...",
 *         resultJson="{...}", latencyMs=192, createdAt=1767225600000}
 *   处理: Mapper 执行 insert，数据库分配自增 id=1
 *   输出: 返回受影响行数 1；副作用是 po.getId() 变成 1L，且该表多一行
 *   续: selectBySessionIdDesc("s-3f9c1a77e2b04d15") 返回的正是这一行（最近的在前）
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>插入必须回填自增主键：响应的 assessmentId 就是它，App 拿它做记录列表的 key 与错题关联，
 *       拿不到 id 这条链路就断了 —— useGeneratedKeys / keyProperty 写在 XML 里，最容易被改漏。</li>
 *   <li>只声明方法、不写 SQL 注解：SQL 集中在 XML 里，改查询条件不用重编译，也便于直接审 SQL
 *       （与 IConversationTurnDao 同一约定）。</li>
 *   <li>带参方法都标 @Param：靠它绑定 #{id} / #{sessionId} / #{turnId}，去掉会报 Parameter 找不到。</li>
 *   <li>列表查询按 created_at 倒序而不是按 id 倒序：id 顺序与时间顺序在正常情况下一致，
 *       但手工补数据或迁移时会分叉，记录页要的是「最近跟读的在最上面」这个业务事实。</li>
 * </ul>
 */
@Mapper
public interface IPronunciationAssessmentDao {

    /** 插一条评测记录，并把数据库生成的自增主键回填到入参对象上。 */
    int insert(PronunciationAssessmentPO po);

    /** 按自增主键查单条评测记录。 */
    PronunciationAssessmentPO selectById(@Param("id") Long id);

    /** 按会话查全部评测记录，按创建时间倒序（最近的在前）。 */
    List<PronunciationAssessmentPO> selectBySessionIdDesc(@Param("sessionId") String sessionId);

    /** 按回合查评测记录，按创建时间倒序。 */
    List<PronunciationAssessmentPO> selectByTurnId(@Param("turnId") Long turnId);
}

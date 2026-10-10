package cn.adinall.ai.infrastructure.dao;

import cn.adinall.ai.infrastructure.dao.po.TrainingSessionPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * training_session 表的 MyBatis DAO（会话档案的插入、查询与状态更新）
 * <p>
 * 功能：只有三个动作 —— 插一条、按 id 查、按 id 改状态；SQL 写在
 * resources/mybatis/mapper/TrainingSessionMapper.xml。
 * <pre>
 *   insert(sessionPO)          插一条档案；主键是我方生成的 sessionId（s-xxxx，不是自增）
 *   selectById(id)             按 sessionId 查档案，查不到返回 null
 *   updateStatus(sessionPO)    动态 SQL：status 必改，error_message / ended_at 传了值才覆盖
 * </pre>
 * 体系架构（谁调它、为什么只要三个动作）：
 * <pre>
 *   ConversationSessionRepository（唯一调用方）
 *        |
 *        +--> save(aggregate)    -> insert(toPO(aggregate))      建会话时落档案
 *        +--> update(aggregate)  -> updateStatus(po)             状态变化时同步
 *        |                           po = {id, status, errorMessage, endedAt}
 *        v
 *   ITrainingSessionDao（本接口，@Mapper）
 *        |  由 PersistenceConfiguration 的 @MapperScan 生成代理
 *        v
 *   TrainingSessionMapper.xml
 *        |
 *        v
 *   training_session 表（id 为 varchar 主键；列名靠下划线转驼峰映射到 PO 字段）
 * </pre>
 * 运行过程（会话收尾时的一次状态更新）：
 * <pre>
 *   会话状态变成 CLOSED / ERROR
 *        |
 *        v
 *   ConversationSessionRepository.update(aggregate)
 *        |
 *        | po.setId(sessionId); po.setStatus("CLOSED");
 *        | po.setErrorMessage(null);
 *        | 终态 -> po.setEndedAt(System.currentTimeMillis())
 *        v
 *   sessionDao.updateStatus(po)
 *        |
 *        v
 *   update training_session
 *        &lt;set&gt;
 *          status = #{status},
 *          &lt;if test="errorMessage != null"&gt;error_message = #{errorMessage},&lt;/if&gt;
 *          &lt;if test="endedAt != null"&gt;ended_at = #{endedAt},&lt;/if&gt;
 *        &lt;/set&gt;
 *        where id = #{id}
 *        |
 *        v
 *   该行的 status 与 ended_at 更新，error_message 保持原值不被抹掉
 * </pre>
 * 案例：
 * <pre>
 *   输入: insert 的 PO = TrainingSessionPO{id="s-3f9c1a77e2b04d15", deviceId="dev-001",
 *         avatarId="anchor", model="flashhead", voice="en-US-AriaNeural",
 *         scenarioId="free", status="QUEUED", startedAt=1730000000000,
 *         endedAt=null, errorMessage=null}
 *   处理: insert into training_session (id, device_id, avatar_id, model, voice,
 *         scenario_id, status, started_at, ended_at, error_message) values (...)
 *   输出: 返回受影响行数 1；该表多一行，ended_at 与 error_message 为 null
 *   续: updateStatus({id="s-3f9c1a77e2b04d15", status="CLOSED",
 *         errorMessage=null, endedAt=1730000300000})
 *       -> status="CLOSED"、ended_at=1730000300000，error_message 仍为 null
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>会话结束、出错都走 updateStatus 这一条：用 XML 的动态 SQL 按需拼字段，
 *       而不是写三个方法 —— 三个方法迟早出现「只改了状态没写结束时间」的漏更新。</li>
 *   <li>error_message 与 ended_at 只在传了值时才覆盖：避免一次普通的状态同步
 *       把上一次的错误信息或结束时间抹掉。</li>
 *   <li>insert 不带 useGeneratedKeys：主键是我方按 s-xxxx 规则生成的 sessionId，
 *       数据库不自增，所以回填无从谈起（对照 IConversationTurnDao 的自增 id）。</li>
 *   <li>PO 字段名与列名的对应靠 MyBatis 的下划线转驼峰（session_id -> sessionId），
 *       PO 上不写任何映射注解。</li>
 * </ul>
 */
@Mapper
public interface ITrainingSessionDao {

    /** 插一条会话档案，主键是我方生成的 sessionId（不是数据库自增）。 */
    int insert(TrainingSessionPO sessionPO);

    /** 按 sessionId 查会话档案；查不到返回 null。 */
    TrainingSessionPO selectById(@Param("id") String id);

    /**
     * 按 id 更新状态；errorMessage 非空时一并写入，endedAt 非空时一并写入。
     * 会话结束、出错都走这一条，所以用动态 SQL 而不是三个方法。
     */
    int updateStatus(TrainingSessionPO sessionPO);
}
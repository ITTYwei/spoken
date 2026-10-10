package cn.adinall.ai.infrastructure.dao;

import cn.adinall.ai.infrastructure.dao.po.TrainingSessionPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 会话档案表的 DAO。
 *
 * 只有三个动作：插一条、按 id 查、按 id 改状态。
 * SQL 写在 resources/mybatis/mapper/TrainingSessionMapper.xml，改查询条件不用重编译。
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
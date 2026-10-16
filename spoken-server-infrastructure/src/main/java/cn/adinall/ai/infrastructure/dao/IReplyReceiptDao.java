package cn.adinall.ai.infrastructure.dao;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 回复完成凭据的数据访问契约，以唯一键实现事务内去重。 */
@Mapper
public interface IReplyReceiptDao {
    /** 插入回复完成凭据；唯一键冲突时不写入，返回实际插入行数。 */
    int claim(@Param("sessionId") String sessionId, @Param("replyId") String replyId);
}

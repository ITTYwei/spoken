package cn.adinall.ai.infrastructure.dao;

import cn.adinall.ai.infrastructure.dao.po.AccountProfilePO;
import org.apache.ibatis.annotations.*;

/** 资料及明确偏好的SQL契约；所有用户资源查询和修改均携带归属条件。 */
@Mapper
public interface IAccountProfileDao {
    AccountProfilePO profile(@Param("userId") long userId);
    int nickname(@Param("userId") long userId, @Param("nickname") String nickname);
    int display(@Param("userId") long userId, @Param("subtitles") boolean subtitles, @Param("translation") String translation);
    int selection(@Param("userId") long userId, @Param("avatarId") String avatarId, @Param("voice") String voice);
    int ensureLearner(@Param("subject") String subject);
    /** 与教学记忆写入共用画像行锁，删除后迟到回合不能恢复旧事实。 */
    Long lockLearner(@Param("subject") String subject);
    long latestTurn(@Param("userId") long userId);
    int resetContext(@Param("subject") String subject, @Param("turn") long turn);
    int deleteFact(@Param("subject") String subject, @Param("key") String key);
    int saveFact(@Param("subject") String subject, @Param("key") String key, @Param("type") String type,
                 @Param("content") String content, @Param("turn") long turn);
}

package cn.adinall.ai.infrastructure.dao;

import cn.adinall.ai.infrastructure.dao.po.AccountProfilePO;
import org.apache.ibatis.annotations.*;

/** 资料及明确偏好的SQL契约；所有用户资源查询和修改均携带归属条件。 */
@Mapper
public interface IAccountProfileDao {
    /** 读取账号的个人资料记录。 */
    AccountProfilePO profile(@Param("userId") long userId);
    /** 更新指定账号的昵称。 */
    int nickname(@Param("userId") long userId, @Param("nickname") String nickname);
    /** 更新字幕显示和翻译偏好。 */
    int display(@Param("userId") long userId, @Param("subtitles") boolean subtitles, @Param("translation") String translation);
    /** 保存账号选择的数字人形象与音色。 */
    int selection(@Param("userId") long userId, @Param("avatarId") String avatarId, @Param("voice") String voice);
    /** 在主体尚无学习档案时创建默认档案。 */
    int ensureLearner(@Param("subject") String subject);
    /** 与教学记忆写入共用画像行锁，删除后迟到回合不能恢复旧事实。 */
    Long lockLearner(@Param("subject") String subject);
    /** 读取账号最新的有效用户回合标识。 */
    long latestTurn(@Param("userId") long userId);
    /** 将记忆上下文起点推进到指定回合。 */
    int resetContext(@Param("subject") String subject, @Param("turn") long turn);
    /** 删除指定主体下的个人事实。 */
    int deleteFact(@Param("subject") String subject, @Param("key") String key);
    /** 保存带来源回合的个人事实。 */
    int saveFact(@Param("subject") String subject, @Param("key") String key, @Param("type") String type,
                 @Param("content") String content, @Param("turn") long turn);
}

package cn.adinall.ai.infrastructure.dao;

import cn.adinall.ai.infrastructure.dao.po.ConversationTurnPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 回合表的 DAO（一问一答）。
 *
 * 注意插入必须回填自增主键：App 的跟读评测拿 turnId 当键，拿不到 id 这条链路就断了。
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
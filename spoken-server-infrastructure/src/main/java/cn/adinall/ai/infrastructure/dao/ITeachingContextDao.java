package cn.adinall.ai.infrastructure.dao;

import org.apache.ibatis.annotations.*;
import java.util.*;

/**
 * 功能：定义教学回复、能力证据和长期记忆的有界数据库访问。
 * <p>体系架构 / 运行过程：</p>
 * <pre>domain repositories -> mapper XML -> scoped SQL</pre>
 * <p>案例：subject不匹配 -> 历史查询返回空。</p>
 * <p>设计要点：</p>
 * <ul><li>SQL只使用参数绑定。</li><li>时间存DATETIME，映射时显式转毫秒仅供Java Instant使用。</li></ul>
 */
@Mapper
public interface ITeachingContextDao {
    void ensureProfile(@Param("subject") String subject);
    String level(@Param("subject") String subject);
    /** 独立的手动档位列，null 表示自动；所有 SQL 按可信主体隔离。 */
    String manualLevel(@Param("subject") String subject);
    /** 覆盖手动选择或置空，不写 english_level 及升级时间。 */
    void setManualLevel(@Param("subject") String subject, @Param("level") String level);
    int promote(@Param("subject") String subject,@Param("from") String from,@Param("to") String to,@Param("session") String session);
    void evidence(Map<String,Object> values);
    List<Map<String,Object>> evidenceSince(@Param("subject") String subject,@Param("since") Date since);
    /** 页面能力描述不读取内部升级消耗边界。 */
    List<Map<String,Object>> descriptionEvidence(@Param("subject") String subject,@Param("since") Date since);
    void beginReply(Map<String,Object> values);
    int completeReply(@Param("turn") Long turn,@Param("reply") String reply,@Param("text") String text);
    List<Map<String,Object>> history(@Param("subject") String subject,@Param("session") String session);
    void saveMemory(Map<String,Object> values);
    List<Map<String,Object>> memories(@Param("subject") String subject,@Param("now") Date now);
    void forget(@Param("subject") String subject,@Param("key") String key);
    void forgetAll(@Param("subject") String subject);
    void forgetHistoryBefore(@Param("subject") String subject,@Param("turn") Long turn);
    void forgetMatching(@Param("subject") String subject,@Param("topic") String topic);
}

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
    /** 在主体尚无学习档案时创建默认档案。 */
    void ensureProfile(@Param("subject") String subject);
    /** 读取主体或会话当前生效的学习档位。 */
    String level(@Param("subject") String subject);
    /** 独立的手动档位列，null 表示自动；所有 SQL 按可信主体隔离。 */
    String manualLevel(@Param("subject") String subject);
    /** 覆盖手动选择或置空，不写 english_level 及升级时间。 */
    void setManualLevel(@Param("subject") String subject, @Param("level") String level);
    /** 仅在原档位仍匹配时提交升级，防止并发评估覆盖新结果。 */
    int promote(@Param("subject") String subject,@Param("from") String from,@Param("to") String to,@Param("session") String session);
    /** 持久化本轮学习证据。 */
    void evidence(Map<String,Object> values);
    /** 读取指定时间之后的学习证据。 */
    List<Map<String,Object>> evidenceSince(@Param("subject") String subject,@Param("since") Date since);
    /** 页面能力描述不读取内部升级消耗边界。 */
    List<Map<String,Object>> descriptionEvidence(@Param("subject") String subject,@Param("since") Date since);
    /** 写入待播放的教学回复决策，不提前记录为已播出。 */
    void beginReply(Map<String,Object> values);
    /** 按回合与回复标识回填已实际播出的教学文本。 */
    int completeReply(@Param("turn") Long turn,@Param("reply") String reply,@Param("text") String text);
    /** 读取指定主体或会话的历史记录。 */
    List<Map<String,Object>> history(@Param("subject") String subject,@Param("session") String session);
    /** 保存长期记忆及其来源信息。 */
    void saveMemory(Map<String,Object> values);
    /** 读取主体当前仍有效的长期记忆。 */
    List<Map<String,Object>> memories(@Param("subject") String subject,@Param("now") Date now);
    /** 删除指定主体下的目标记忆。 */
    void forget(@Param("subject") String subject,@Param("key") String key);
    /** 删除指定主体下的全部长期记忆。 */
    void forgetAll(@Param("subject") String subject);
    /** 推进记忆历史起点，使指定回合之前的原话不再参与记忆提取。 */
    void forgetHistoryBefore(@Param("subject") String subject,@Param("turn") Long turn);
    /** 删除主体下与指定话题匹配的长期记忆。 */
    void forgetMatching(@Param("subject") String subject,@Param("topic") String topic);
}

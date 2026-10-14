package cn.adinall.ai.infrastructure.dao;

import cn.adinall.ai.infrastructure.dao.po.RecentLessonPO;
import org.apache.ibatis.annotations.*;

/** 最近课程 SQL 契约，所有读写必须包含来自后端会话的可信主体。 */
@Mapper
public interface IRecentLessonDao {
    /** 主体唯一键保证不同账号没有共享最近课。 */
    @Select("SELECT textbook_id, lesson_id FROM learner_recent_lesson WHERE subject_key=#{subject}")
    @Results({@Result(column="textbook_id",property="textbookId"),@Result(column="lesson_id",property="lessonId")})
    RecentLessonPO find(@Param("subject") String subject);
    /** 单条 upsert 保证主体最近课程原子覆盖；不保存本次题号。 */
    @Insert("INSERT INTO learner_recent_lesson(subject_key,textbook_id,lesson_id) VALUES(#{subject},#{book},#{lesson}) " +
            "ON DUPLICATE KEY UPDATE textbook_id=VALUES(textbook_id),lesson_id=VALUES(lesson_id),updated_at=CURRENT_TIMESTAMP(3)")
    void save(@Param("subject") String subject, @Param("book") String book, @Param("lesson") String lesson);
}

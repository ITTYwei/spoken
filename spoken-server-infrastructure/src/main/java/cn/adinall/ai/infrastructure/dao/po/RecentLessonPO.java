package cn.adinall.ai.infrastructure.dao.po;

import lombok.Data;

/** 最近课数据库行，仅供基础设施转换，不向领域暴露数据库结构。 */
@Data
public class RecentLessonPO {
    /** 保存时的本项目教材编号，不可空。 */
    private String textbookId;
    /** 保存时的本项目课编号，不包含进度或题号。 */
    private String lessonId;
}

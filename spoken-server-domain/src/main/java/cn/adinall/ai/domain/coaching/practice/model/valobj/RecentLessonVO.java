package cn.adinall.ai.domain.coaching.practice.model.valobj;

/**
 * 持久化的最近课程引用，不保存题号或伪造练习进度。
 * @param textbookId 本项目教材编号
 * @param lessonId 本项目课编号
 */
public record RecentLessonVO(String textbookId, String lessonId) { }

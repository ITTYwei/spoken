package cn.adinall.ai.domain.coaching.practice.model.valobj;

/**
 * 当前练习状态快照；属于 practice，供按钮、Agent 与 SSE 共同使用。
 * @param mode FREE 或 COURSE；新会话始终 FREE
 * @param textbookId 课程模式的教材编号，自由模式为空
 * @param lessonId 课程模式的课编号，自由模式为空
 * @param lessonTitle 当前课名，自由模式为空
 * @param version 单会话单调递增版本，用于拒绝迟到操作
 * @param pendingContinue 已实际播出继续询问后才为 true
 * @param recentTextbookId 最近真实选中教材，未练过则为空
 * @param recentLessonId 最近真实选中课程，未练过则为空
 * @param recentLessonTitle 最近课名，目录失效时不提供
 */
public record PracticeStateVO(String mode, String textbookId, String lessonId, String lessonTitle,
                              long version, boolean pendingContinue, String recentTextbookId,
                              String recentLessonId, String recentLessonTitle) { }

package cn.adinall.ai.domain.content.model.valobj;

/**
 * 一课的轻量目录条目，用于手机浏览和 Agent 查询候选，不能作为当前课程上下文。
 * 点击后再读取完整 TextLessonVO，避免把数百课原题一起发送给手机或模型。
 * @param id 稳定课程编号，查看目录不会改变练习状态
 * @param title 原题课名或本项目整理的课名
 * @param objective 本项目编写的练习目标
 * @param questionCount 完整课中的原题条目数；Part 2 包含总题、要点和收尾追问
 */
public record LessonBriefVO(String id, String title, String objective, int questionCount) {
    /** 从完整课投影摘要，无题目裁剪或用户状态变化。 */
    public static LessonBriefVO from(TextLessonVO lesson) {
        return new LessonBriefVO(lesson.id(), lesson.title(), lesson.objective(), lesson.questions().size());
    }
}

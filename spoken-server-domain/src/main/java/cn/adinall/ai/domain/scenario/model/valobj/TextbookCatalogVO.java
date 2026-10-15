package cn.adinall.ai.domain.scenario.model.valobj;

import java.util.List;

/**
 * 教材轻量目录：总量与返回条目分开，Agent 展示部分候选时不会冒充完整目录。
 * 手机目录返回全部摘要，具体原题只通过课详情读取；来源信息保留用于预览署名。
 * @param id 稳定教材编号
 * @param title 教材名称
 * @param sourceUrl 固定来源地址
 * @param sourceCommit 固定内容提交
 * @param license 内容许可
 * @param part 口语分项，决定题目结构的展示说明
 * @param lessonCount 教材真实总课数，不因候选过滤而改变
 * @param lessons 当前返回的不可变课程摘要列表
 */
public record TextbookCatalogVO(String id, String title, String sourceUrl, String sourceCommit,
                               String license, SpeakingPartEnum part, int lessonCount, List<LessonBriefVO> lessons) {
    public TextbookCatalogVO { lessons = List.copyOf(lessons); }
    /** 将完整定义转换为指定摘要列表；不会把教材原题装入目录响应。 */
    public static TextbookCatalogVO from(TextbookVO book, List<LessonBriefVO> lessons) {
        return new TextbookCatalogVO(book.id(), book.title(), book.sourceUrl(), book.sourceCommit(),
                book.license(), book.part(), book.lessons().size(), lessons);
    }
}

package cn.adinall.ai.domain.scenario.model.valobj;

import java.util.List;

/**
 * 文本教材定义，属于 scenario 领域。只描述课程目录及来源，不保存用户练习状态。
 * 来源提交和许可随资源发布，运行时不访问外部教材服务；例如 BandReady 日常话题。
 * @param id 本项目稳定教材编号
 * @param title 展示名称
 * @param sourceUrl 原始内容项目地址
 * @param sourceCommit 固定参考提交，保证可追溯
 * @param license 原始题目许可
 * @param lessons 完整课列表，不可变且不含用户信息
 */
public record TextbookVO(String id, String title, String sourceUrl, String sourceCommit,
                         String license, List<TextLessonVO> lessons) {
    public TextbookVO { lessons = List.copyOf(lessons); }
}

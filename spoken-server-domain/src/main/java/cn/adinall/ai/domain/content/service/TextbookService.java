package cn.adinall.ai.domain.content.service;

import cn.adinall.ai.domain.content.model.valobj.TextLessonVO;

import cn.adinall.ai.domain.content.adapter.repository.ITextbookRepository;
import cn.adinall.ai.domain.content.model.valobj.*;
import cn.adinall.ai.domain.companion.service.context.reducer.HybridReducer;
import cn.adinall.ai.types.exception.AppException;
import org.springframework.stereotype.Service;
import java.util.List;

/**
 * scenario 领域教材查询和容量规则。仓储只提供定义，服务保证编号存在及完整课程能装入上下文。
 * 超容量时拒绝选课，不切掉题目；例如手工加入长课后，用户得到明确错误而非残缺教材。
 */
@Service
public class TextbookService {
    /** 本地不可变教材定义来源，不参与用户状态事务。 */
    private final ITextbookRepository repository;
    /** 创建 {@code TextbookService}，初始化其依赖与状态。 */
    public TextbookService(ITextbookRepository repository) { this.repository = repository; }
    /** 获取发布版本的目录，无副作用。 */
    public List<TextbookVO> list() { return repository.list(); }
    /**
     * 手机目录只投影课名、目标和真实题数，点击详情后才加载原题。
     * 不读取或切换用户会话，浏览几百节课也不会把它们装入模型上下文。
     */
    public List<TextbookCatalogVO> catalog() {
        return list().stream().map(book -> TextbookCatalogVO.from(book,
                book.lessons().stream().map(LessonBriefVO::from).toList())).toList();
    }
    /**
     * Agent 目录查询返回有限候选与真实总量，避免完整题库撑爆单轮工具结果。
     * 原话明确引用课名/编号时优先返回相关课；否则每本最多返回三课供澄清。
     * 这里只缩小只读候选，不执行选课；模糊请求仍须用户确认，不能自动选择第一课。
     * @param utterance 可信调用上下文中的当前原话，不能由模型替换为其他用户输入
     */
    public List<TextbookCatalogVO> agentCatalog(String utterance) {
        String text = utterance == null ? "" : utterance.toLowerCase(java.util.Locale.ROOT);
        return list().stream().map(book -> {
            List<TextLessonVO> matches = book.lessons().stream().filter(l -> l.explicitlyReferenced(text)).toList();
            return TextbookCatalogVO.from(book, (matches.isEmpty() ? book.lessons() : matches).stream()
                    .limit(3).map(LessonBriefVO::from).toList());
        }).toList();
    }
    /** 两级编号均须匹配；容量采用项目现有保守字节估计，失败不修改练习状态。 */
    public TextLessonVO lesson(String book, String lesson) {
        TextLessonVO found = list().stream().filter(b -> b.id().equals(book)).flatMap(b -> b.lessons().stream())
                .filter(l -> l.id().equals(lesson)).findFirst()
                .orElseThrow(() -> new AppException("lesson_not_found", "教材或课程不存在"));
        if (HybridReducer.estimate(found.context()) > 1400)
            throw new AppException("lesson_too_large", "本课内容超过当前上下文容量，请拆分课程");
        return found;
    }
}

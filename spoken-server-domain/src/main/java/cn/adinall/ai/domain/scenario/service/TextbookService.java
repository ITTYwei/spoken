package cn.adinall.ai.domain.scenario.service;

import cn.adinall.ai.domain.scenario.adapter.repository.ITextbookRepository;
import cn.adinall.ai.domain.scenario.model.valobj.*;
import cn.adinall.ai.domain.agent.service.context.reducer.HybridReducer;
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
    public TextbookService(ITextbookRepository repository) { this.repository = repository; }
    /** 获取发布版本的目录，无副作用。 */
    public List<TextbookVO> list() { return repository.list(); }
    /** 两级编号均须匹配；容量采用项目现有保守字节估计，失败不修改练习状态。 */
    public TextLessonVO lesson(String book, String lesson) {
        var found = list().stream().filter(b -> b.id().equals(book)).flatMap(b -> b.lessons().stream())
                .filter(l -> l.id().equals(lesson)).findFirst()
                .orElseThrow(() -> new AppException("lesson_not_found", "教材或课程不存在"));
        if (HybridReducer.estimate(found.context()) > 1400)
            throw new AppException("lesson_too_large", "本课内容超过当前上下文容量，请拆分课程");
        return found;
    }
}

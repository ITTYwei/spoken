package cn.adinall.ai.infrastructure.adapter.repository;

import cn.adinall.ai.domain.scenario.adapter.repository.ITextbookRepository;
import cn.adinall.ai.domain.scenario.model.valobj.TextbookVO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Repository;
import java.io.IOException;
import java.util.List;

/**
 * 基础设施教材适配器。启动时读取固定资源并冻结，运行时不访问 GitHub 或额外服务。
 * JSON 缺失、重复编号或空题组均阻止启动，避免把损坏内容交给数字人。
 * 原始题卡另存 provenance 目录，供许可及题目追溯，不加入每轮模型上下文。
 */
@Repository
public class ResourceTextbookRepository implements ITextbookRepository {
    /** 随应用发布的教材快照，与登录主体无关。 */
    private final List<TextbookVO> books;
    public ResourceTextbookRepository(ObjectMapper mapper) {
        try (var input = new ClassPathResource("textbooks/bandready-daily.json").getInputStream()) {
            var book = mapper.readValue(input, TextbookVO.class);
            if (book.lessons().isEmpty() || book.lessons().stream().anyMatch(l -> l.questions().isEmpty()) ||
                    book.lessons().stream().map(l -> l.id()).distinct().count() != book.lessons().size())
                throw new IllegalStateException("教材目录或题组无效");
            books = List.of(book);
        } catch (IOException e) { throw new IllegalStateException("无法加载本地文本教材", e); }
    }
    /** 返回启动时验证的不可变资源。 */
    @Override public List<TextbookVO> list() { return books; }
}

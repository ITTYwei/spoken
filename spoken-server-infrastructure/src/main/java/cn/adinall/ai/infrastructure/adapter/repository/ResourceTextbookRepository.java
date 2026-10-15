package cn.adinall.ai.infrastructure.adapter.repository;

import cn.adinall.ai.domain.scenario.adapter.repository.ITextbookRepository;
import cn.adinall.ai.domain.scenario.model.valobj.TextbookVO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Repository;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * 本地教材仓储适配器：根据发布清单加载全部口语教材，运行时不访问 GitHub。
 * 只解析资源并检查发布结构，不推断教学模式、不写用户状态；容量规则仍属于 scenario 服务。
 * 任意文件缺失、重复教材/课程编号或空题组均阻止启动，避免部分导入后向用户展示残缺目录。
 * 旧三课保留原编号；完整口语来源快照仅用于追溯，不会整体进入模型上下文。
 */
@Repository
public class ResourceTextbookRepository implements ITextbookRepository {
    /** 启动时一次加载的不可变教材定义，对所有账号相同，不含用户学习记录。 */
    private final List<TextbookVO> books;

    /**
     * 先加载显式清单，再逐一解析固定资源；全部检查成功后才发布仓储快照。
     * 路径必须位于教材目录且为 JSON，避免清单误指向其他应用资源。
     * @param mapper 项目统一 JSON 解析器
     * @throws IllegalStateException 清单、资源或唯一编号无效，不允许静默忽略坏文件
     */
    public ResourceTextbookRepository(ObjectMapper mapper) {
        try {
            String[] paths;
            try (var input = new ClassPathResource("textbooks/catalog.json").getInputStream()) {
                paths = mapper.readValue(input, String[].class);
            }
            if (paths.length == 0) throw new IllegalStateException("教材清单为空");
            var loaded = new ArrayList<TextbookVO>();
            var bookIds = new HashSet<String>();
            for (String path : paths) {
                if (!path.matches("[a-z0-9/-]+\\.json") || path.contains(".."))
                    throw new IllegalStateException("教材清单路径无效");
                try (var input = new ClassPathResource("textbooks/" + path).getInputStream()) {
                    var book = mapper.readValue(input, TextbookVO.class);
                    var lessonIds = new HashSet<String>();
                    if (book.id() == null || !bookIds.add(book.id()) || book.lessons().isEmpty())
                        throw new IllegalStateException("教材编号重复或目录为空");
                    for (var lesson : book.lessons()) {
                        if (lesson.id() == null || !lessonIds.add(lesson.id()) || lesson.questions().isEmpty() ||
                                lesson.questions().stream().anyMatch(q -> q == null || q.isBlank()))
                            throw new IllegalStateException("课程编号重复或原题为空：" + book.id());
                    }
                    loaded.add(book);
                }
            }
            books = List.copyOf(loaded);
        } catch (IOException e) {
            throw new IllegalStateException("无法加载本地文本教材", e);
        }
    }
    /** 返回已完整验证的不可变快照；无外部请求或用户数据副作用。 */
    @Override public List<TextbookVO> list() { return books; }
}

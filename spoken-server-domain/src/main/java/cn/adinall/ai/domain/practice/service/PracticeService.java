package cn.adinall.ai.domain.practice.service;

import cn.adinall.ai.domain.practice.adapter.repository.IRecentLessonRepository;
import cn.adinall.ai.domain.practice.model.aggregate.PracticeAggregate;
import cn.adinall.ai.domain.practice.model.valobj.RecentLessonVO;
import org.springframework.stereotype.Service;
import java.util.concurrent.ConcurrentHashMap;

/**
 * practice 领域生命周期与最近课服务。运行态仅随业务会话生存，不从数据库恢复题号。
 * Case 负责身份和目录校验后初始化；关闭及替换旧会话时移除，防止长期占用内存。
 */
@Service
public class PracticeService {
    /** 主体隔离的最近课程仓储；不可由模型传入其他主体。 */
    private final IRecentLessonRepository recent;
    /** 活跃业务会话的练习状态，不是 ADK 历史缓存。 */
    private final ConcurrentHashMap<String, PracticeAggregate> sessions = new ConcurrentHashMap<>();
    public PracticeService(IRecentLessonRepository recent) { this.recent = recent; }
    /** 读取真实最近课程，目录是否仍有效由 Case 协调 scenario 校验。 */
    public java.util.Optional<RecentLessonVO> recent(String subject) { return recent.find(subject); }
    /** 每个新业务会话只初始化一次；任何最近记录都不自动进入课程。 */
    public PracticeAggregate initialize(String session, String book, String lesson, String title) {
        return sessions.computeIfAbsent(session, id -> new PracticeAggregate(book, lesson, title));
    }
    /** 仅供已校验业务会话的 Case 访问；旧测试路径可懒初始化自由状态。 */
    public PracticeAggregate state(String session) { return initialize(session, null, null, null); }
    /** 写入最近课程；仓储失败冒泡，避免宣布未保存的课程已经选中。 */
    public void remember(String subject, String book, String lesson) { recent.save(subject, new RecentLessonVO(book, lesson)); }
    /** 关闭或回收会话时调用，不删除主体最近课。 */
    public void close(String session) { sessions.remove(session); }
}

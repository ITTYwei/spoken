package cn.adinall.ai.infrastructure.adapter.repository;

import java.util.ArrayList;

import cn.adinall.ai.domain.companion.adapter.repository.IChatHistoryRepository;
import cn.adinall.ai.domain.companion.model.valobj.DialogMessageVO;
import cn.adinall.ai.infrastructure.dao.ITeachingContextDao;
import org.springframework.stereotype.Repository;
import java.util.*;

/**
 * 功能：按可信主体查询最近有效教学问答，不读取旧Prompt或未完成回复。
 * <p>体系架构 / 运行过程：</p>
 * <pre>scoped SQL limit32 -> chronological pairs -> HistoryProvider</pre>
 * <p>案例：取消回合completed=0 -> 查询排除。</p>
 * <p>设计要点：</p>
 * <ul><li>SQL再次验证training_session归属。</li><li>返回时间顺序。</li></ul>
 */
@Repository
public class ChatHistoryRepository implements IChatHistoryRepository {
    /** SQL边界。 */ private final ITeachingContextDao dao;
    /** 创建 {@code ChatHistoryRepository}，初始化其依赖与状态。 */
    public ChatHistoryRepository(ITeachingContextDao dao) { this.dao=dao; }
    /** 读取指定主体范围内的近期记录。 */
    public List<DialogMessageVO> recent(String subject,String session) {
        ArrayList<DialogMessageVO> list=new ArrayList<>(dao.history(subject,session).stream().map(m -> new DialogMessageVO(
                ((Number)m.get("turnId")).longValue(),(String)m.get("userText"),(String)m.get("assistantText"),
                Boolean.TRUE.equals(m.get("practiceInput")) || (m.get("practiceInput") instanceof Number n && n.intValue()!=0))).toList());
        Collections.reverse(list); return list;
    }
}

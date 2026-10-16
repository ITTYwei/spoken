package cn.adinall.ai.infrastructure.adapter.repository;

import cn.adinall.ai.domain.coaching.teaching.adapter.repository.ITeachingReplyRepository;
import cn.adinall.ai.domain.coaching.teaching.model.valobj.TeachingDecisionVO;
import cn.adinall.ai.infrastructure.dao.ITeachingContextDao;
import org.springframework.stereotype.Repository;
import java.util.Map;

/**
 * 功能：保存任务决策及服务端完整播报状态。
 * <p>体系架构 / 运行过程：</p>
 * <pre>case begin -> completed=false -> media drain -> complete by replyId</pre>
 * <p>案例：旧轮次迟到提交不能命中新轮次replyId。</p>
 * <p>设计要点：</p>
 * <ul><li>回复原文独立于SSE订阅保存。</li><li>不假装有手机播放确认。</li></ul>
 */
@Repository
public class TeachingReplyRepository implements ITeachingReplyRepository {
    /** 有界SQL访问。 */ private final ITeachingContextDao dao;
    /** 创建 {@code TeachingReplyRepository}，初始化其依赖与状态。 */
    public TeachingReplyRepository(ITeachingContextDao dao) { this.dao=dao; }
    /** 将待播放教学决策序列化并写入教学记录。 */
    public void begin(String subject,String session,Long turn,String reply,TeachingDecisionVO d) {
        // 数据库继续保存原文本码，避免枚举重构依赖 MyBatis 默认类型处理器或改变既有历史。
        dao.beginReply(Map.of("turn",turn,"subject",subject,"session",session,"reply",reply,"level",d.level().name(),
                "action",d.nextTask().action(),"target",d.nextTask().target(),"outcome",d.outcome().code(),"practice",d.practiceInput(),"reliable",d.reliableAssessment()));
    }
    /** 仅更新匹配回合与回复标识的已播教学文本。 */
    public void complete(Long turn,String reply,String text) { dao.completeReply(turn,reply,text); }
}

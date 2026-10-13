package cn.adinall.ai.infrastructure.adapter.repository;

import cn.adinall.ai.domain.conversation.adapter.repository.ITeachingReplyRepository;
import cn.adinall.ai.domain.conversation.model.valobj.TeachingDecisionVO;
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
    public TeachingReplyRepository(ITeachingContextDao dao) { this.dao=dao; }
    public void begin(String subject,String session,Long turn,String reply,TeachingDecisionVO d) {
        dao.beginReply(Map.of("turn",turn,"subject",subject,"session",session,"reply",reply,"level",d.level().name(),
                "action",d.nextTask().action(),"target",d.nextTask().target(),"outcome",d.outcome(),"practice",d.practiceInput(),"reliable",d.reliableAssessment()));
    }
    public void complete(Long turn,String reply,String text) { dao.completeReply(turn,reply,text); }
}

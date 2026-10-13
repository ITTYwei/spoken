package cn.adinall.ai.infrastructure.adapter.repository;

import cn.adinall.ai.domain.learner.adapter.repository.ILearnerProgressRepository;
import cn.adinall.ai.domain.learner.model.valobj.*;
import cn.adinall.ai.infrastructure.dao.ITeachingContextDao;
import org.springframework.stereotype.Repository;
import java.time.Instant;
import java.util.*;

/**
 * 功能：实现六级档案与回答证据存储，SQL过滤已结束会话和已用升级证据。
 * <p>体系架构 / 运行过程：</p>
 * <pre>learner -> bound mapper -> profile / recent evidence</pre>
 * <p>案例：同一等级并发提升 -> 只有一次CAS成功。</p>
 * <p>设计要点：</p>
 * <ul><li>日期列用Date绑定。</li><li>最近查询最多500条，不无限加载历史。</li></ul>
 */
@Repository
public class LearnerProgressRepository implements ILearnerProgressRepository {
    /** SQL访问接口。 */ private final ITeachingContextDao dao;
    public LearnerProgressRepository(ITeachingContextDao dao) { this.dao=dao; }
    public EnglishLevelEnum level(String subject) { dao.ensureProfile(subject); return EnglishLevelEnum.valueOf(dao.level(subject)); }
    public void record(LearningEvidenceVO e) {
        dao.evidence(Map.of("turn",e.turnId(),"subject",e.subjectKey(),"session",e.sessionId(),"outcome",e.outcome(),
                "candidate",e.candidate().name(),"confidence",e.confidence(),"eligible",e.eligible(),"at",Date.from(e.observedAt())));
    }
    public List<LearningEvidenceVO> recent(String subject,Instant since) {
        return dao.evidenceSince(subject,Date.from(since)).stream().map(m -> new LearningEvidenceVO(subject,(String)m.get("sessionId"),
                ((Number)m.get("turnId")).longValue(),(String)m.get("outcome"),EnglishLevelEnum.valueOf((String)m.get("candidate")),
                ((Number)m.get("confidence")).doubleValue(),((Number)m.get("eligible")).intValue()==1,
                Instant.ofEpochMilli(((Number)m.get("observedAt")).longValue()))).toList();
    }
    public boolean promote(String subject,EnglishLevelEnum from,EnglishLevelEnum to,String session) {
        return dao.promote(subject,from.name(),to.name(),session)==1;
    }
}

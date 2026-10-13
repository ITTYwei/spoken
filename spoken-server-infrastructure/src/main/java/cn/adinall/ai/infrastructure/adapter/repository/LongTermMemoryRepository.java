package cn.adinall.ai.infrastructure.adapter.repository;

import cn.adinall.ai.domain.agent.adapter.repository.ILongTermMemoryRepository;
import cn.adinall.ai.domain.agent.model.entity.LongTermMemoryEntity;
import cn.adinall.ai.infrastructure.dao.ITeachingContextDao;
import org.springframework.stereotype.Repository;
import java.time.Instant;
import java.util.*;

/**
 * 功能：按主体和事实键覆盖、更正、删除记忆，排除过期内容。
 * <p>体系架构 / 运行过程：</p>
 * <pre>memory service -> scoped SQL -> latest explicit facts</pre>
 * <p>案例：忘掉所有记忆只影响指定subject。</p>
 * <p>设计要点：</p>
 * <ul><li>采用参考项目repository/dao结构。</li><li>召回最多120条再由领域选三条。</li></ul>
 */
@Repository
public class LongTermMemoryRepository implements ILongTermMemoryRepository {
    /** SQL访问端口。 */ private final ITeachingContextDao dao;
    public LongTermMemoryRepository(ITeachingContextDao dao) { this.dao=dao; }
    public void save(LongTermMemoryEntity m) {
        dao.saveMemory(Map.of("subject",m.subjectKey(),"key",m.memoryKey(),"type",m.type(),"content",m.content(),"session",m.sessionId(),
                "turn",m.turnId(),"confirmation",m.confirmation(),"confirmed",Date.from(m.confirmedAt()),"expires",Date.from(m.expiresAt())));
    }
    public List<LongTermMemoryEntity> active(String subject,Instant now) {
        return dao.memories(subject,Date.from(now)).stream().map(m -> new LongTermMemoryEntity(subject,(String)m.get("memoryKey"),
                (String)m.get("memoryType"),(String)m.get("content"),(String)m.get("sourceSession"),((Number)m.get("sourceTurn")).longValue(),
                (String)m.get("confirmation"),Instant.ofEpochMilli(((Number)m.get("confirmedAt")).longValue()),
                Instant.ofEpochMilli(((Number)m.get("expiresAt")).longValue()))).toList();
    }
    public void forget(String subject,String key) { dao.forget(subject,key); }
    public void forgetAll(String subject) { dao.forgetAll(subject); }
    public void forgetHistoryBefore(String subject,Long turn) { dao.forgetHistoryBefore(subject,turn); }
    /** 先锁定并推进主体边界，再删事实；迟到写入按相同主体边界检查，避免删除后复活。 */
    @org.springframework.transaction.annotation.Transactional
    public void forgetAllAndHistory(String subject,Long turn) { dao.forgetHistoryBefore(subject,turn); dao.forgetAll(subject); }
    @org.springframework.transaction.annotation.Transactional
    public void forgetMatchingAndHistory(String subject,String topic,Long turn) { dao.forgetHistoryBefore(subject,turn); dao.forgetMatching(subject,topic); }
}

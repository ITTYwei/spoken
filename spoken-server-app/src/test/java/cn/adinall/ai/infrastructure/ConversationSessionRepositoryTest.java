package cn.adinall.ai.infrastructure;

import cn.adinall.ai.infrastructure.dao.po.TrainingSessionPO;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronization;

import cn.adinall.ai.infrastructure.adapter.repository.ConversationSessionRepository;
import cn.adinall.ai.infrastructure.dao.ITrainingSessionDao;
import cn.adinall.ai.domain.coaching.conversation.model.aggregate.ConversationSessionAggregate;
import cn.adinall.ai.domain.coaching.conversation.model.valobj.SessionStatusEnum;
import org.junit.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.mockito.Mockito.*;
import static org.junit.Assert.*;

/**
 * <b>功能</b>：验证持久会话提交前不泄露活跃索引。
 * <p><b>体系架构 / 运行过程</b></p>
 * <pre>insert -> pending transaction -> no runtime index
commit -> afterCommit -> publish runtime index</pre>
 * <p><b>案例</b>：插入抛异常 -> findById 为空；提交成功 -> 两条索引同时可见。</p>
 * <b>设计要点</b>
 * <ul><li>模拟真实事务回调顺序，不连接数据库。</li><li>覆盖提交、插入失败及回滚路径。</li></ul>
 */
public class ConversationSessionRepositoryTest {
    private ConversationSessionAggregate session() {
        return new ConversationSessionAggregate("s","p","d","a","m","v","free",SessionStatusEnum.READY);
    }
    @Test public void indexIsPublishedAfterCommit() {
        ConversationSessionRepository repository=new ConversationSessionRepository(mock(ITrainingSessionDao.class));
        ConversationSessionAggregate session=session();
        TransactionSynchronizationManager.initSynchronization();
        try {
            repository.save(session);
            assertTrue(repository.findById("s").isEmpty());
            for (TransactionSynchronization callback:TransactionSynchronizationManager.getSynchronizations()) callback.afterCommit();
            assertSame(session, repository.findById("s").orElseThrow());
            assertSame(session, repository.findByDeviceId("d").orElseThrow());
        } finally { TransactionSynchronizationManager.clearSynchronization(); }
    }
    @Test public void rollbackDoesNotPublishIndex() {
        ConversationSessionRepository repository=new ConversationSessionRepository(mock(ITrainingSessionDao.class));
        TransactionSynchronizationManager.initSynchronization();
        try {
            repository.save(session());
            for (TransactionSynchronization callback:TransactionSynchronizationManager.getSynchronizations())
                callback.afterCompletion(org.springframework.transaction.support.TransactionSynchronization.STATUS_ROLLED_BACK);
            assertTrue(repository.findById("s").isEmpty());
        } finally { TransactionSynchronizationManager.clearSynchronization(); }
    }
    @Test public void insertFailureLeavesNoGhostSession() {
        ITrainingSessionDao dao=mock(ITrainingSessionDao.class); doThrow(new IllegalStateException("insert failed")).when(dao).insert(any());
        ConversationSessionRepository repository=new ConversationSessionRepository(dao);
        assertThrows(IllegalStateException.class, () -> repository.save(session()));
        assertTrue(repository.findById("s").isEmpty());
        assertTrue(repository.findByDeviceId("d").isEmpty());
    }
    @Test public void persistenceUsesCapturedOwnerWithoutRequestThread() {
        ITrainingSessionDao dao = mock(ITrainingSessionDao.class);
        ConversationSessionAggregate aggregate = session();
        aggregate.bindSubject("user:7");
        new ConversationSessionRepository(dao).save(aggregate);
        ArgumentCaptor<TrainingSessionPO> saved = org.mockito.ArgumentCaptor.forClass(cn.adinall.ai.infrastructure.dao.po.TrainingSessionPO.class);
        verify(dao).insert(saved.capture());
        assertEquals(Long.valueOf(7), saved.getValue().getUserId());
    }
}

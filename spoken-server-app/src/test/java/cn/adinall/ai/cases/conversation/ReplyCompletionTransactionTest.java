package cn.adinall.ai.cases.conversation;

import cn.adinall.ai.domain.coaching.teaching.service.TeachingService;
import cn.adinall.ai.cases.conversation.runtime.ReplyArchiveCase;
import cn.adinall.ai.domain.coaching.conversation.model.aggregate.ConversationSessionAggregate;
import cn.adinall.ai.domain.coaching.conversation.model.valobj.ConversationEventVO;
import java.util.concurrent.atomic.AtomicInteger;
import org.mybatis.spring.SqlSessionFactoryBean;

import cn.adinall.ai.cases.conversation.runtime.*;
import cn.adinall.ai.domain.coaching.conversation.adapter.port.*;
import cn.adinall.ai.domain.coaching.conversation.adapter.repository.*;
import cn.adinall.ai.domain.coaching.teaching.adapter.repository.ITeachingReplyRepository;
import cn.adinall.ai.domain.coaching.conversation.model.aggregate.*;
import cn.adinall.ai.domain.coaching.conversation.model.valobj.*;
import cn.adinall.ai.domain.coaching.teaching.model.valobj.TeachingDecisionVO;
import cn.adinall.ai.domain.coaching.teaching.model.valobj.TeachingOutcomeEnum;
import cn.adinall.ai.domain.coaching.teaching.model.valobj.TeachingPlaybackVO;
import cn.adinall.ai.domain.coaching.teaching.model.valobj.TeachingTaskVO;
import cn.adinall.ai.domain.learning.model.valobj.EnglishLevelEnum;
import cn.adinall.ai.infrastructure.adapter.repository.*;
import cn.adinall.ai.infrastructure.dao.*;
import org.junit.*;
import org.mybatis.spring.*;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import javax.sql.DataSource;
import java.util.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** Real Spring transaction + MyBatis + H2: rollback must include the durable deduplication receipt. */
public class ReplyCompletionTransactionTest {
    /** 每个测试独立持有教学运行态，并显式共享给相关应用协作者。 */
    private TeachingService teachingStates;

    private AnnotationConfigApplicationContext context;
    private JdbcTemplate sql;
    private ConversationServiceImpl service;
    private ConversationSessionAggregate session;
    private ITeachingReplyRepository teaching;

    @Before public void setup() {
        context = new AnnotationConfigApplicationContext(Config.class);
        teachingStates = context.getBean(TeachingService.class);
        sql = new JdbcTemplate(context.getBean(DataSource.class));
        sql.execute("CREATE TABLE conversation_turn (id BIGINT AUTO_INCREMENT PRIMARY KEY, session_id VARCHAR(64), seq INT, user_text TEXT, assistant_text TEXT, audio_path VARCHAR(200), latency_ms BIGINT, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        sql.execute("CREATE TABLE conversation_teaching_reply (turn_id BIGINT PRIMARY KEY, reply_id VARCHAR(191), completed INT, spoken_text TEXT)");
        sql.execute("CREATE TABLE conversation_reply_receipt (session_id VARCHAR(64), reply_id VARCHAR(191), PRIMARY KEY(session_id,reply_id))");
        sql.update("INSERT INTO conversation_turn (id,session_id,seq,user_text) VALUES (10,'s',0,'Hi')");
        sql.update("INSERT INTO conversation_teaching_reply (turn_id,reply_id,completed) VALUES (10,'r1',0)");
        session = new ConversationSessionAggregate("s","p","d","a","m","v","free",SessionStatusEnum.ACTIVE);
        session.beginReply("r1"); session.bindReplyTurn(10L);
        teachingStates.state(session.getSessionId()).pendingPlayback(new TeachingPlaybackVO(10L,"r1",new TeachingDecisionVO(EnglishLevelEnum.L2,
                TeachingTaskVO.question(""),TeachingOutcomeEnum.RESPONDED,true,"",false,false)));
        teaching = context.getBean(ITeachingReplyRepository.class);
        service = new ConversationServiceImpl(mock(IAvatarDriverPort.class), mock(IConversationSessionRepository.class),
                context.getBean(IConversationTurnRepository.class), mock(IConversationEventBusPort.class),
                context.getBean(ReplyArchiveCase.class), new ConversationRuntime(), mock(IReplyCompletionPort.class), mock(IOpeningEncounterRepository.class), teachingStates);
    }
    @After public void close() { context.close(); }

    @Test public void failureRollsBackAllWritesAndSameReceiptCanRetry() {
        AtomicInteger attempts = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(invocation -> {
            invocation.callRealMethod();
            if (attempts.getAndIncrement() == 0) throw new IllegalStateException("failure after teaching write");
            return null;
        }).when(teaching).complete(10L,"r1","What do you like?");
        ConversationEventVO done = ConversationEventVO.of(EventTypeEnum.ASSISTANT_DONE, Map.of("replyId","r1"));
        session.appendAssistantDelta("What do you like?");
        try { service.applyDriverEvent(session,done); fail("must propagate persistence failure"); }
        catch (IllegalStateException expected) { }
        assertFalse(session.replyCompleted());
        assertEquals("What do you like?", session.assistantText(null));
        assertEquals("", teachingStates.state(session.getSessionId()).pending().question());
        assertNull(sql.queryForObject("SELECT assistant_text FROM conversation_turn WHERE id=10",String.class));
        assertEquals(0, (int)sql.queryForObject("SELECT completed FROM conversation_teaching_reply WHERE turn_id=10",Integer.class));
        assertEquals(0, (int)sql.queryForObject("SELECT COUNT(*) FROM conversation_reply_receipt",Integer.class));

        service.applyDriverEvent(session,done);
        service.applyDriverEvent(session,done);
        assertTrue(session.replyCompleted());
        assertEquals("What do you like?", teachingStates.state(session.getSessionId()).pending().question());
        assertEquals("What do you like?",sql.queryForObject("SELECT assistant_text FROM conversation_turn WHERE id=10",String.class));
        assertEquals(1, (int)sql.queryForObject("SELECT completed FROM conversation_teaching_reply WHERE turn_id=10",Integer.class));
        assertEquals(1, (int)sql.queryForObject("SELECT COUNT(*) FROM conversation_reply_receipt",Integer.class));
        verify(teaching,times(2)).complete(10L,"r1","What do you like?");
    }

    @Test public void durableReceiptPreventsDuplicateAssistantOnlyArchiveAcrossRuntimeCopies() {
        ReplyArchiveCase archive=context.getBean(ReplyArchiveCase.class);
        ConversationSessionAggregate first=new ConversationSessionAggregate("s","p","d","a","m","v","free",SessionStatusEnum.ACTIVE);
        first.beginReply("opening"); first.bindReplyTurn(null);
        archive.complete(first,"Hello",null);
        ConversationSessionAggregate duplicate=new ConversationSessionAggregate("s","p","d","a","m","v","free",SessionStatusEnum.ACTIVE);
        duplicate.beginReply("opening"); duplicate.bindReplyTurn(null);
        archive.complete(duplicate,"Hello",null);
        assertEquals(1,(int)sql.queryForObject("SELECT COUNT(*) FROM conversation_turn WHERE user_text IS NULL",Integer.class));
    }

    @Configuration @EnableTransactionManagement(proxyTargetClass=true)
    public static class Config {
        @Bean public TeachingService teachingStates() { return new TeachingService(); }
        @Bean public DataSource dataSource() { return new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa",""); }
        @Bean public PlatformTransactionManager transactionManager(DataSource ds) { return new DataSourceTransactionManager(ds); }
        @Bean public SqlSessionTemplate template(DataSource ds) throws Exception {
            SqlSessionFactoryBean factory=new SqlSessionFactoryBean(); factory.setDataSource(ds);
            org.apache.ibatis.session.Configuration configuration=new org.apache.ibatis.session.Configuration(); configuration.setMapUnderscoreToCamelCase(true);
            factory.setConfiguration(configuration);
            factory.setMapperLocations(new ClassPathResource("mybatis/mapper/ConversationTurnMapper.xml"),
                    new ClassPathResource("mybatis/mapper/TeachingContextMapper.xml"),new ClassPathResource("mybatis/mapper/ReplyReceiptMapper.xml"));
            return new SqlSessionTemplate(factory.getObject());
        }
        @Bean public IConversationTurnRepository turns(SqlSessionTemplate template) { return new ConversationTurnRepository(template.getMapper(IConversationTurnDao.class)); }
        @Bean public ITeachingReplyRepository teaching(SqlSessionTemplate template) { return spy(new TeachingReplyRepository(template.getMapper(ITeachingContextDao.class))); }
        @Bean public IReplyReceiptRepository receipts(SqlSessionTemplate template) { return new ReplyReceiptRepository(template.getMapper(IReplyReceiptDao.class)); }
        @Bean public ReplyArchiveCase archive(IConversationTurnRepository turns, ITeachingReplyRepository teaching, IReplyReceiptRepository receipts, TeachingService teachingStates) { return new ReplyArchiveCase(turns, teaching, receipts, teachingStates); }
    }
}

package cn.adinall.ai.cases.conversation;

import cn.adinall.ai.infrastructure.dao.ITeachingContextDao;
import cn.adinall.ai.infrastructure.adapter.repository.*;
import cn.adinall.ai.domain.agent.model.entity.LongTermMemoryEntity;
import cn.adinall.ai.domain.learner.model.valobj.*;
import cn.adinall.ai.domain.conversation.model.valobj.*;
import org.junit.*;
import org.flywaydb.core.Flyway;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.sql.*;
import java.time.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import static org.junit.Assert.*;

/**
 * 功能：在随机临时库验证V12、身份隔离、升级证据消耗和有效回复查询。
 * <p>体系架构 / 运行过程：</p>
 * <pre>explicit flag -> random DB -> Flyway -> actual MyBatis repositories -> drop test DB</pre>
 * <p>案例：completed=false的回复不召回；遗忘边界过滤旧原文。</p>
 * <p>设计要点：</p>
 * <ul><li>默认跳过网络访问。</li><li>只删除本测试创建的随机库，不写业务数据。</li></ul>
 */
public class TeachingDatabaseIntegrationTest {
    /** 仅随机测试库的管理员连接。 */ private static Connection admin;
    /** 本轮随机库名，不接受外部输入。 */ private static String schema;
    /** MyBatis测试会话。 */ private static org.apache.ibatis.session.SqlSession sql;
    /** 当前测试DAO。 */ private static ITeachingContextDao dao;
    @BeforeClass public static void setup() throws Exception {
        Assume.assumeTrue(Boolean.getBoolean("teachingDbIntegration"));
        String yaml=Files.readString(Path.of("src/main/resources/application-dev.yml"));
        String url=value(yaml,"url"),user=value(yaml,"username"),password=value(yaml,"password");
        schema="teaching_verify_"+UUID.randomUUID().toString().replace("-","");
        admin=DriverManager.getConnection(url,user,password);
        admin.createStatement().execute("CREATE DATABASE `"+schema+"` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        String scratch=url.replaceFirst("/spoken(?=\\?|$)","/"+schema);
        if(scratch.equals(url)) throw new IllegalStateException("Refusing business database");
        Flyway.configure().dataSource(scratch,user,password).locations("classpath:db/migration").load().migrate();
        var factory=new SqlSessionFactoryBean(); factory.setDataSource(new DriverManagerDataSource(scratch,user,password));
        factory.setMapperLocations(new ClassPathResource("mybatis/mapper/TeachingContextMapper.xml"));
        sql=factory.getObject().openSession(true); dao=sql.getMapper(ITeachingContextDao.class);
    }
    @AfterClass public static void cleanup() throws Exception {
        if(sql!=null) sql.close();
        if(admin!=null) { try { if(schema!=null && schema.matches("teaching_verify_[a-f0-9]{32}")) admin.createStatement().execute("DROP DATABASE `"+schema+"`"); } finally {admin.close();} }
    }
    private static String value(String yaml,String key) {
        var m=Pattern.compile("(?m)^\\s+"+key+":\\s*\"?([^\"\\r\\n]+)\"?\\s*$").matcher(yaml);
        if(!m.find()) throw new IllegalStateException("Missing datasource field"); return m.group(1).trim();
    }
    private void exec(String statement,Object...params) throws Exception {
        try(var p=admin.prepareStatement("USE `"+schema+"`")) {p.execute();}
        try(var p=admin.prepareStatement(statement)) {for(int i=0;i<params.length;i++) p.setObject(i+1,params[i]); p.execute();}
    }
    @Test public void scopedMemoryCorrectionAndForget() {
        var repository=new LongTermMemoryRepository(dao); var now=Instant.now();
        dao.ensureProfile("device:d1");
        repository.save(new LongTermMemoryEntity("device:d1","preference:coffee","PREFERENCE","I like coffee","s1",1L,"EXPLICIT",now,now.plusSeconds(60)));
        assertEquals(1,repository.active("device:d1",now).size()); assertTrue(repository.active("user:1",now).isEmpty());
        repository.save(new LongTermMemoryEntity("device:d1","preference:coffee","PREFERENCE","I don't like coffee","s1",2L,"EXPLICIT",now,now.plusSeconds(60)));
        assertEquals(1,repository.active("device:d1",now).size()); assertEquals("I don't like coffee",repository.active("device:d1",now).get(0).content());
        repository.save(new LongTermMemoryEntity("device:d1","preference:coffee","PREFERENCE","stale preference","s1",1L,"EXPLICIT",now.plusSeconds(1),now.plusSeconds(60)));
        assertEquals("I don't like coffee",repository.active("device:d1",now).get(0).content());
        repository.forgetMatchingAndHistory("device:d1","coffee",2L); assertTrue(repository.active("device:d1",now).isEmpty());
        repository.save(new LongTermMemoryEntity("device:d1","preference:coffee","PREFERENCE","late forgotten content","s1",1L,"EXPLICIT",now,now.plusSeconds(60)));
        assertTrue(repository.active("device:d1",now).isEmpty());
        repository.save(new LongTermMemoryEntity("device:d1","preference:coffee","PREFERENCE","new explicit preference","s1",3L,"EXPLICIT",now,now.plusSeconds(60)));
        assertEquals("new explicit preference",repository.active("device:d1",now).get(0).content());
    }
    @Test public void onlyCompletedRepliesWithMatchingSubjectAreHistory() throws Exception {
        dao.ensureProfile("device:history-device");
        exec("INSERT INTO training_session(id,device_id,status,started_at) VALUES('hs','history-device','CLOSED',?)",System.currentTimeMillis());
        exec("INSERT INTO conversation_turn(id,session_id,seq,user_text) VALUES(91001,'hs',1,'I like music')");
        var replies=new TeachingReplyRepository(dao); var history=new ChatHistoryRepository(dao);
        var d=new TeachingDecisionVO(EnglishLevelEnum.L2,TeachingTaskVO.question(""),"RESPONDED",true,"",false,true);
        replies.begin("device:history-device","hs",91001L,"hr",d);
        assertTrue(history.recent("device:history-device","hs").isEmpty()); replies.complete(91001L,"hr","What music do you like?");
        assertEquals(1,history.recent("device:history-device","hs").size());
        assertTrue(history.recent("device:history-device","hs").get(0).practiceInput());
        assertTrue(history.recent("user:1","hs").isEmpty());
        new LongTermMemoryRepository(dao).forgetAllAndHistory("device:history-device",91001L);
        assertTrue(history.recent("device:history-device","hs").isEmpty());
    }
    @Test public void evidenceIsDeduplicatedAndConsumedAfterOnePromotion() throws Exception {
        var repository=new LearnerProgressRepository(dao); var now=Instant.now().minusSeconds(5);
        assertEquals(EnglishLevelEnum.L2,repository.level("device:grade-device"));
        exec("INSERT INTO training_session(id,device_id,status,started_at) VALUES('gs','grade-device','CLOSED',?)",System.currentTimeMillis());
        var e=new LearningEvidenceVO("device:grade-device","gs",92001L,"ANSWERED",EnglishLevelEnum.L3,.95,true,now);
        repository.record(e); repository.record(e);
        assertEquals(1,repository.recent(e.subjectKey(),now.minusSeconds(10)).size());
        assertTrue(repository.promote(e.subjectKey(),EnglishLevelEnum.L2,EnglishLevelEnum.L3,"new-gs"));
        assertFalse(repository.promote(e.subjectKey(),EnglishLevelEnum.L2,EnglishLevelEnum.L3,"new-gs"));
        assertEquals(EnglishLevelEnum.L3,repository.level(e.subjectKey())); assertTrue(repository.recent(e.subjectKey(),now.minusSeconds(10)).isEmpty());
    }
}

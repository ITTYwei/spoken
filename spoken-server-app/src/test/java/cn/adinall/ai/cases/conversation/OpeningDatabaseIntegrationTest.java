package cn.adinall.ai.cases.conversation;

import cn.adinall.ai.domain.learner.model.valobj.LearnerLevelEnum;

import cn.adinall.ai.domain.conversation.model.valobj.*;
import cn.adinall.ai.domain.identity.model.valobj.OpeningSubjectVO;
import cn.adinall.ai.domain.learner.service.level.RecentLevelPolicy;
import cn.adinall.ai.infrastructure.adapter.repository.*;
import cn.adinall.ai.infrastructure.dao.*;
import cn.adinall.ai.infrastructure.dao.po.TrainingSessionPO;
import org.flywaydb.core.Flyway;
import org.junit.*;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.regex.*;
import static org.junit.Assert.*;

/**
 * 首句数据库集成验证。
 * <p>
 * 功能：在随机临时库迁移 V1～V11，验证真实 MySQL 查询、分档文案和快照保存。
 * <p>
 * 体系架构 / 运行过程：
 * <pre>
 *   显式启用 -> 随机库 -> Flyway -> MyBatis -> 仓储断言 -> 删除该临时库
 * </pre>
 * 案例：
 * <pre>
 *   输入：三场匿名中文历史 + 一场账号私有历史
 *   输出：匿名查询只得到三场，近期水平 LOW；快照 JSON 可读
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>默认不访问网络，以 openingDbIntegration=true 显式启用。</li>
 *   <li>只创建并删除随机 opening_verify 库，不写业务库。</li>
 *   <li>凭证从本地开发配置读取，不进入日志。</li>
 * </ul>
 */
public class OpeningDatabaseIntegrationTest {
    private static Connection admin;
    private static String schema;
    private static DriverManagerDataSource datasource;
    private static org.apache.ibatis.session.SqlSession sqlSession;
    private static IOpeningFactsDao dao;
    private static final Instant NOW=Instant.parse("2026-10-12T09:00:00Z");
    @BeforeClass public static void createDatabase() throws Exception {
        Assume.assumeTrue(Boolean.getBoolean("openingDbIntegration"));
        String yaml=Files.readString(Path.of("src/main/resources/application-dev.yml"));
        String url=config(yaml,"url"), user=config(yaml,"username"), password=config(yaml,"password");
        schema="opening_verify_"+UUID.randomUUID().toString().replace("-","");
        admin=DriverManager.getConnection(url,user,password);
        try(var statement=admin.createStatement()) { statement.execute("CREATE DATABASE `"+schema+"` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci"); }
        String scratch=url.replaceFirst("/spoken(?=\\?|$)","/"+schema);
        if(scratch.equals(url)) throw new IllegalStateException("Unexpected dev schema; refusing business DB migration");
        Flyway.configure().dataSource(scratch,user,password).locations("classpath:db/migration").load().migrate();
        datasource=new DriverManagerDataSource(scratch,user,password);
        var configuration=new org.apache.ibatis.session.Configuration(); configuration.setMapUnderscoreToCamelCase(true);
        var factory=new SqlSessionFactoryBean(); factory.setDataSource(datasource); factory.setConfiguration(configuration);
        factory.setMapperLocations(new ClassPathResource("mybatis/mapper/OpeningFactsMapper.xml"),new ClassPathResource("mybatis/mapper/TrainingSessionMapper.xml"));
        sqlSession=factory.getObject().openSession(true); dao=sqlSession.getMapper(IOpeningFactsDao.class);
        for(int i=0;i<3;i++) {
            session("guest"+i,"device",null,"free",NOW.minus(Duration.ofDays(i+1)).toEpochMilli());
            execute("insert into conversation_turn(session_id,seq,user_text) values(?,0,?)","guest"+i,"我今天去了公园");
        }
        session("private","device",9L,"free",NOW.toEpochMilli()-5000);
        execute("insert into conversation_turn(session_id,seq,user_text) values('private',0,'private account conversation')");
        session("empty","empty-device",null,"free",NOW.toEpochMilli()-4000);
        execute("insert into conversation_turn(session_id,seq,assistant_text) values('empty',0,'Opening only')");
        execute("insert into user_practice_plan(id,user_id,title,status) values(1,9,'Test plan',1)");
        execute("insert into user_practice_plan_item(id,plan_id,user_id,title,status,scheduled_date,opening_topic_zh,opening_topic_en) values(1,1,9,'Topic',0,'2026-10-12','周末安排','weekend plans')");
    }
    @AfterClass public static void removeDatabase() throws Exception {
        if(sqlSession!=null) sqlSession.close();
        if(admin!=null) {
            try { if(schema!=null && schema.matches("opening_verify_[a-f0-9]{32}")) try(var statement=admin.createStatement()) { statement.execute("DROP DATABASE `"+schema+"`"); } }
            finally { admin.close(); }
        }
    }
    private static String config(String yaml,String key) {
        var matcher=Pattern.compile("(?m)^\\s*"+key+":\\s*(.*?)\\s*$").matcher(yaml);
        if(!matcher.find()) throw new IllegalStateException("Missing configuration: "+key);
        return matcher.group(1).replaceAll("^\"|\"$","");
    }
    private static void execute(String sql,Object... args) throws Exception {
        try(var connection=datasource.getConnection(); var statement=connection.prepareStatement(sql)) {
            for(int i=0;i<args.length;i++) statement.setObject(i+1,args[i]); statement.executeUpdate();
        }
        // 测试通过独立JDBC连接模拟外部变化，需清除测试SqlSession缓存后重新查实际数据库。
        if(sqlSession!=null) sqlSession.clearCache();
    }
    private static void session(String id,String device,Long user,String scene,long ended) throws Exception {
        execute("insert into training_session(id,device_id,user_id,scenario_id,status,started_at,ended_at) values(?,?,?,?,'CLOSED',?,?)",id,device,user,scene,ended-60000,ended);
    }
    @Test public void guestHistoryExcludesAccountsAndEmptyOpeningOnlySessions() {
        var repository=new OpeningHistoryRepository(dao);
        var facts=repository.read(new OpeningSubjectVO("device",null,null),NOW,"free");
        assertEquals(3,facts.evidence().size()); assertEquals(LearnerLevelEnum.LOW,RecentLevelPolicy.calculate(facts.evidence(),NOW).level());
        assertFalse(repository.read(new OpeningSubjectVO("empty-device",null,null),NOW,"free").hasUserSpeech());
    }
    @Test public void databaseScenariosHaveSafeThreeLevelOpenings() {
        var repository=new DatabaseScenarioRepository(dao); assertEquals("free",repository.findById("unknown").id());
        for(var scenario:repository.listAll()) for(var level:LearnerLevelEnum.values())
            assertTrue(scenario.id()+"/"+level,cn.adinall.ai.domain.conversation.service.opening.OpeningTextPolicy.valid(scenario.openingFor(level)));
    }
    /** 最近聊天按实际发言排序，完成状态、跟读标记、账号和遗忘边界同时生效。 */
    @Test public void recentActualTopicComesOnlyFromLatestConversation() throws Exception {
        session("topic-old","topic-device",22L,"free",NOW.minusSeconds(100).toEpochMilli());
        session("topic-new","topic-device",22L,"free",NOW.minusSeconds(30).toEpochMilli());
        execute("insert into conversation_turn(id,session_id,seq,user_text) values(992001,'topic-old',0,'I like movies')");
        execute("insert into conversation_turn(id,session_id,seq,user_text) values(992002,'topic-new',0,'I drink coffee')");
        execute("insert into conversation_turn(id,session_id,seq,user_text) values(992003,'topic-new',1,'I like reading books')");
        execute("insert into conversation_turn(id,session_id,seq,user_text) values(992004,'topic-new',2,'I listen to music')");
        for(long turn:List.of(992001L,992002L,992003L,992004L)) {
            execute("insert into conversation_teaching_reply(turn_id,subject_key,session_id,reply_id,english_level,action,outcome,completed,practice_input) values(?,'user:22',?,?,'L2','QUESTION','RESPONDED',?,?)",
                    turn,turn==992001L?"topic-old":"topic-new","topic-r-"+turn,turn==992004L?0:1,turn==992003L?1:0);
        }
        var repository=new OpeningHistoryRepository(dao);
        var subject=new OpeningSubjectVO("topic-device",22L,null);
        var actual=repository.read(subject,NOW,"free").unfinishedTopic();
        assertEquals("咖啡",actual.chinese()); assertTrue(actual.source().contains("topic-new:turn:992002"));
        assertNull(repository.read(new OpeningSubjectVO("topic-device",null,null),NOW,"free").unfinishedTopic());
        assertTrue(dao.topicTurns("topic-new","another-device",23L).isEmpty());
        execute("insert into learner_profile(subject_key,context_reset_turn) values('user:22',992002)");
        assertNull(repository.read(subject,NOW,"free").unfinishedTopic()); // 不返回旧会话电影。
        // 较早建立的长会话后来又有实际发言，应成为最近会话。
        execute("insert into conversation_turn(id,session_id,seq,user_text) values(992005,'topic-old',1,'I enjoy sports')");
        execute("insert into conversation_teaching_reply(turn_id,subject_key,session_id,reply_id,english_level,action,outcome,completed,practice_input) values(992005,'user:22','topic-old','topic-r-992005','L2','QUESTION','RESPONDED',1,0)");
        assertEquals("topic-old",dao.history("topic-device",22L).get(0).getId());
        assertEquals("运动",repository.read(subject,NOW,"free").unfinishedTopic().chinese());
    }
    @Test public void explicitResumeRequiresRealCompletedTurnAndSameScenario() throws Exception {
        assertEquals(1,dao.saveResume("private","周末安排","weekend plans","explicit:turn1"));
        var repository=new OpeningHistoryRepository(dao); var subject=new OpeningSubjectVO("device",9L,null);
        assertNull(repository.read(subject,NOW,"free").unfinishedTopic());
        session("verified-resume","resume-device",10L,"free",NOW.minusSeconds(30).toEpochMilli());
        execute("insert into conversation_turn(id,session_id,seq,user_text) values(990001,'verified-resume',0,'下次聊旅行')");
        execute("insert into conversation_teaching_reply(turn_id,subject_key,session_id,reply_id,english_level,action,outcome,completed) values(990001,'user:10','verified-resume','verified-r','L2','QUESTION','RESPONDED',0)");
        assertEquals(1,dao.verifiedResume("verified-resume",990001L,"旅行","travel"));
        var verified=new OpeningSubjectVO("resume-device",10L,null);
        assertNull(repository.read(verified,NOW,"free").unfinishedTopic());
        execute("update conversation_teaching_reply set completed=1 where turn_id=990001");
        assertEquals("OPEN",dao.history("resume-device",10L).get(0).getResumeState());
        assertEquals("下次聊旅行",dao.history("resume-device",10L).get(0).getResumeSourceText());
        assertEquals("旅行",repository.read(verified,NOW,"free").unfinishedTopic().chinese());
        assertNull(repository.read(verified,NOW,"interview").unfinishedTopic());
        execute("update training_session set resume_state='SENSITIVE' where id='verified-resume'");
        assertNull(repository.read(verified,NOW,"free").unfinishedTopic());
        execute("update training_session set resume_state='CLOSED' where id='verified-resume'");
        assertNull(repository.read(verified,NOW,"free").unfinishedTopic());
        execute("insert into conversation_turn(id,session_id,seq,user_text) values(990002,'verified-resume',1,'换个话题')");
        assertEquals(1,dao.finishTopic("verified-resume",990002L));
        assertEquals(0,dao.verifiedResume("verified-resume",990001L,"旅行","travel"));
        assertNull(repository.read(verified,NOW,"free").unfinishedTopic());
        assertNull(repository.read(subject,NOW,"interview").unfinishedTopic());
        assertNull(repository.read(new OpeningSubjectVO("device",null,null),NOW,"free").unfinishedTopic());
    }
    @Test public void introductionRecordIsSubjectScopedAndSurvivesEmptyConversation() {
        var repository=new OpeningEncounterRepository(dao);
        assertFalse(repository.read("device:intro-device").introduced());
        repository.recordShown("device:intro-device","intro-session","avatarA",null,true,NOW);
        assertTrue(repository.read("device:intro-device").introduced());
        assertFalse(repository.read("user:123").introduced());
        repository.recordShown("device:intro-device","new-session","avatarB","小白",false,NOW.plusSeconds(1));
        repository.recordShown("device:intro-device","late-old","avatarA","小安",false,NOW.minusSeconds(1));
        assertEquals("avatarB",repository.read("device:intro-device").lastAvatarId());
        assertTrue(repository.read("device:intro-device").introduced());
    }
    @Test public void plansRequireAuthenticationAndTodayScheduling() {
        var repository=new OpeningPlanRepository(dao);
        assertTrue(repository.findToday(null,LocalDate.of(2026,10,12),"free").isEmpty());
        assertTrue(repository.findToday(9L,LocalDate.of(2026,10,11),"free").isEmpty());
        assertEquals("weekend plans",repository.findToday(9L,LocalDate.of(2026,10,12),"free").orElseThrow().english());
    }
    @Test public void sessionMapperStoresRealJsonSnapshotWithoutChangingAuditType() throws Exception {
        var po=new TrainingSessionPO(); po.setId("snapshot"); po.setDeviceId("snapshot-device"); po.setStatus("READY");
        po.setStartedAt(NOW.toEpochMilli()); po.setOpeningDecisionJson("{\"initialLevel\":\"LOW\",\"text\":\"你好，今天做什么？\"}");
        assertEquals(1,sqlSession.getMapper(ITrainingSessionDao.class).insert(po));
        assertTrue(sqlSession.getMapper(ITrainingSessionDao.class).selectById("snapshot").getOpeningDecisionJson().contains("LOW"));
        try(var connection=datasource.getConnection(); var statement=connection.createStatement();var rows=statement.executeQuery("select data_type from information_schema.columns where table_schema='"+schema+"' and table_name='training_session' and column_name='created_at'")) {
            assertTrue(rows.next()); assertEquals("datetime",rows.getString(1));
        }
    }
}

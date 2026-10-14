package cn.adinall.ai.domain.account;

import cn.adinall.ai.domain.account.service.security.TokenHasher;

import cn.adinall.ai.domain.account.support.AccountContext;
import cn.adinall.ai.domain.account.adapter.repository.IAccountRepository;
import cn.adinall.ai.domain.account.adapter.repository.IProfileRepository;
import cn.adinall.ai.domain.account.service.impl.AccountService;

import cn.adinall.ai.infrastructure.adapter.repository.AccountRepository;
import cn.adinall.ai.infrastructure.adapter.repository.ProfileRepository;
import cn.adinall.ai.types.exception.AppException;
import org.flywaydb.core.Flyway;
import org.junit.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;
import static org.junit.Assert.*;

/** 在随机临时MySQL库验证迁移、真实事务、令牌撤销及画像隔离；默认不连接网络。 */
public class AccountDatabaseIntegrationTest {
    private static Connection admin;
    private static String schema;
    private static AnnotationConfigApplicationContext context;
    private static JdbcTemplate jdbc;
    private static IAccountRepository accounts;
    private static IProfileRepository profiles;
    private static org.apache.ibatis.session.SqlSession sql;
    private static cn.adinall.ai.infrastructure.dao.ITeachingContextDao teaching;
    /** 在临时库验证最近课程迁移及真实 Mapper，不能只用内存替身验证持久化。 */
    private static cn.adinall.ai.infrastructure.dao.IRecentLessonDao recentLessons;
    @Configuration @EnableTransactionManagement
    public static class Transactions { }
    @BeforeClass public static void setup() throws Exception {
        Assume.assumeTrue(Boolean.getBoolean("accountDbIntegration"));
        String yaml = Files.readString(Path.of("src/main/resources/application-dev.yml"));
        String url = value(yaml, "url"), username = value(yaml, "username"), password = value(yaml, "password");
        schema = "account_verify_" + UUID.randomUUID().toString().replace("-", "");
        String scratch = url.replaceFirst("/spoken(?=\\?|$)", "/" + schema);
        if (scratch.equals(url)) throw new IllegalStateException("拒绝操作业务库");
        admin = DriverManager.getConnection(url, username, password);
        admin.createStatement().execute("CREATE DATABASE `" + schema + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        var datasource = new DriverManagerDataSource(scratch, username, password);
        Flyway.configure().dataSource(datasource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(datasource);
        var factory = new org.mybatis.spring.SqlSessionFactoryBean();
        factory.setDataSource(datasource);
        factory.setMapperLocations(
                new org.springframework.core.io.ClassPathResource("mybatis/mapper/AccountMapper.xml"),
                new org.springframework.core.io.ClassPathResource("mybatis/mapper/AccountProfileMapper.xml"),
                new org.springframework.core.io.ClassPathResource("mybatis/mapper/TeachingContextMapper.xml"));
        var sessionFactory = factory.getObject();
        sessionFactory.getConfiguration().addMapper(cn.adinall.ai.infrastructure.dao.IRecentLessonDao.class);
        // SqlSessionTemplate参与Spring事务；不能用自动提交的独立SqlSession验证仓储原子性。
        var template = new org.mybatis.spring.SqlSessionTemplate(sessionFactory);
        // 通过Spring代理调用真实事务，不能用new仓储绕过@Transactional验证。
        context = new AnnotationConfigApplicationContext();
        context.registerBean("jdbc", JdbcTemplate.class, () -> jdbc);
        context.registerBean("transactionManager", DataSourceTransactionManager.class, () -> new DataSourceTransactionManager(datasource));
        context.registerBean(cn.adinall.ai.infrastructure.dao.IAccountDao.class,
                () -> template.getMapper(cn.adinall.ai.infrastructure.dao.IAccountDao.class));
        context.registerBean(cn.adinall.ai.infrastructure.dao.IAccountProfileDao.class,
                () -> template.getMapper(cn.adinall.ai.infrastructure.dao.IAccountProfileDao.class));
        context.register(Transactions.class, AccountRepository.class, ProfileRepository.class);
        context.refresh();
        accounts = context.getBean(IAccountRepository.class); profiles = context.getBean(IProfileRepository.class);
        sql = sessionFactory.openSession(true);
        teaching = sql.getMapper(cn.adinall.ai.infrastructure.dao.ITeachingContextDao.class);
        recentLessons = sql.getMapper(cn.adinall.ai.infrastructure.dao.IRecentLessonDao.class);
    }
    @AfterClass public static void cleanup() throws Exception {
        AccountContext.clear();
        if (sql != null) sql.close();
        if (context != null) context.close();
        if (admin != null) {
            // 只删除本测试创建的随机库，不接受来自用户或配置的删除目标。
            try { if (schema != null && schema.matches("account_verify_[a-f0-9]{32}")) admin.createStatement().execute("DROP DATABASE `" + schema + "`"); }
            finally { admin.close(); }
        }
    }
    private static String value(String yaml, String key) {
        var matcher = Pattern.compile("(?m)^\\s*" + key + ":\\s*([^\\r\\n#]+)").matcher(yaml);
        if (!matcher.find()) throw new IllegalStateException("缺少数据库配置字段");
        return matcher.group(1).trim().replaceAll("^['\"]|['\"]$", "");
    }
    /** 最近课程只覆盖同一主体，其他账号隔离，重启读取也只是引用而非具体题号。 */
    @Test public void recentLessonsPersistAndRemainIsolated() {
        assertNull(recentLessons.find("user:course-a"));
        recentLessons.save("user:course-a","bandready-daily","food-cooking");
        recentLessons.save("user:course-b","bandready-daily","daily-home");
        recentLessons.save("user:course-a","bandready-daily","food-shopping");
        assertEquals("food-shopping",recentLessons.find("user:course-a").getLessonId());
        assertEquals("daily-home",recentLessons.find("user:course-b").getLessonId());
    }
    @Test public void registrationIsAtomicAndPasswordChangeBlocksStaleIssuance() {
        var principal = accounts.register("atomic_user", "old-hash", UUID.randomUUID().toString().replace("-", ""));
        int count = jdbc.queryForObject("SELECT COUNT(*) FROM app_user", Integer.class);
        try { accounts.register("atomic_user", "another-hash", UUID.randomUUID().toString().replace("-", "")); fail(); }
        catch (AppException e) { assertEquals("account_exists", e.code()); }
        assertEquals(count, jdbc.queryForObject("SELECT COUNT(*) FROM app_user", Integer.class).intValue());
        var old = accounts.credential("atomic_user");
        String hash = TokenHasher.digest("long-random-test-token-for-atomic-user");
        assertTrue(accounts.issue(old, hash, Instant.now().plusSeconds(3600)));
        assertNotNull(accounts.authenticate(hash, Instant.now()));
        assertTrue(accounts.changePassword(principal.id(), "old-hash", "new-hash"));
        assertNull(accounts.authenticate(hash, Instant.now()));
        assertFalse(accounts.issue(old, TokenHasher.digest("late-old-password-login"), Instant.now().plusSeconds(3600)));
        assertFalse(accounts.changePassword(principal.id(), "old-hash", "unexpected-hash"));
        var fresh = accounts.credential("atomic_user");
        String expired = TokenHasher.digest("expired-token-test");
        assertTrue(accounts.issue(fresh, expired, Instant.now().minusSeconds(1)));
        assertNull(accounts.authenticate(expired, Instant.now()));
    }
    @Test public void settingsAndFactsAreScopedToTheAuthenticatedAccount() {
        var a = accounts.register("profile_a", "hash-a", UUID.randomUUID().toString().replace("-", ""));
        var b = accounts.register("profile_b", "hash-b", UUID.randomUUID().toString().replace("-", ""));
        profiles.nickname(a.id(), "新昵称"); profiles.display(a.id(), false, "off"); profiles.selection(a.id(), "anchor", "Cherry");
        assertEquals("新昵称", profiles.get(a.id()).nickname());
        assertFalse(profiles.get(a.id()).subtitles());
        assertTrue(profiles.get(b.id()).subtitles()); assertNull(profiles.get(b.id()).voice());
        String session = "s-profile-a";
        jdbc.update("INSERT INTO training_session(id,user_id,device_id,status,started_at) VALUES(?,?,?,'CLOSED',?)", session, a.id(), "same-device", System.currentTimeMillis());
        jdbc.update("INSERT INTO conversation_turn(session_id,seq,user_text) VALUES(?,0,'my goal')", session);
        Long turn = jdbc.queryForObject("SELECT MAX(id) FROM conversation_turn WHERE session_id=?", Long.class, session);
        assertTrue(accounts.ownsSession(a.id(), session)); assertFalse(accounts.ownsSession(b.id(), session));
        profiles.manageFact(a.id(), "learning_goal", "GOAL", "练习旅行交流");
        profiles.manageFact(b.id(), "learning_goal", "GOAL", "练习工作交流");
        profiles.manageFact(a.id(), "learning_goal", "GOAL", null);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM agent_long_term_memory WHERE subject_key=?", Integer.class, "user:" + a.id()).intValue());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM agent_long_term_memory WHERE subject_key=?", Integer.class, "user:" + b.id()).intValue());
        // 调用实际MyBatis写入SQL：删除前的旧回合迟到，不得让目标复活。
        java.util.Date now = new java.util.Date();
        teaching.saveMemory(java.util.Map.of("subject", "user:" + a.id(), "key", "learning_goal", "type", "GOAL", "content", "旧目标",
                "session", session, "turn", turn, "confirmation", "EXPLICIT", "confirmed", now, "expires", new java.util.Date(now.getTime() + 60000)));
        assertEquals(0, teaching.memories("user:" + a.id(), now).size());
        teaching.evidence(java.util.Map.of("turn", turn, "subject", "user:" + a.id(), "session", session, "outcome", "ANSWERED",
                "candidate", "L3", "confidence", .95, "eligible", true, "at", now));
        // 展示证据与账号一致；教学升档后页面证据仍然可用。
        assertEquals(1, teaching.descriptionEvidence("user:" + a.id(), new java.util.Date(0)).size());
        assertEquals(0, teaching.descriptionEvidence("user:" + b.id(), new java.util.Date(0)).size());
        teaching.promote("user:" + a.id(), "L2", "L3", "promotion-session");
        assertEquals(1, teaching.descriptionEvidence("user:" + a.id(), new java.util.Date(0)).size());
    }
}

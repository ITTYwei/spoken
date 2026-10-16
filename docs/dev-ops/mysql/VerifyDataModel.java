import java.nio.file.Files;
import java.nio.file.Path;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.time.LocalDateTime;
import cn.adinall.ai.infrastructure.dao.DatabaseTime;
import cn.adinall.ai.infrastructure.dao.IConversationTurnDao;
import cn.adinall.ai.infrastructure.dao.IPronunciationAssessmentDao;
import cn.adinall.ai.infrastructure.dao.ITrainingSessionDao;
import cn.adinall.ai.infrastructure.dao.po.ConversationTurnPO;
import cn.adinall.ai.infrastructure.dao.po.PronunciationAssessmentPO;
import cn.adinall.ai.infrastructure.dao.po.TrainingSessionPO;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;

/** Runs real MySQL migrations and regression cases in two disposable databases only. */
class VerifyDataModel {
    private static int assertions;

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Expected project directory and configuration path");
        Path migrations = Path.of(args[0], "spoken-server-app/src/main/resources/db/migration");
        String config = Files.readString(Path.of(args[1]));
        // Extract connection authority only. Never use the configured business schema or print credentials.
        Matcher authority = Pattern.compile("jdbc:mysql://([^/\\s]+)/").matcher(config);
        if (!authority.find()) throw new IllegalArgumentException("MySQL URL missing in configuration");
        String url = "jdbc:mysql://" + authority.group(1)
                + "/?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai"
                + "&connectTimeout=5000&socketTimeout=20000";
        Properties credentials = new Properties();
        credentials.setProperty("user", setting(config, "username"));
        credentials.setProperty("password", setting(config, "password"));
        try (Connection admin = DriverManager.getConnection(url, credentials)) {
            check(scalar(admin, "SELECT @@innodb_page_size") == 16384, "InnoDB 16KB pages");
            System.out.println("MySQL " + admin.getMetaData().getDatabaseProductVersion());
            List<Path> versions;
            try (java.util.stream.Stream<Path> files = Files.list(migrations)) {
                versions = files.filter(p -> p.getFileName().toString().matches("V\\d+__.*\\.sql"))
                        .sorted(Comparator.comparingInt(VerifyDataModel::version)).toList();
            }
        check(versions.size() == 10, "V1 through V10 present");
            runScenario(admin, url, credentials, versions, false);
            runScenario(admin, url, credentials, versions, true);
        }
        System.out.println("PASS: " + assertions + " assertions; fresh and V8 upgrade databases removed.");
    }

    private static String setting(String config, String name) {
        Matcher m = Pattern.compile("(?m)^\\s+" + name + ":\\s*(.+?)\\s*$").matcher(config);
        if (!m.find()) throw new IllegalArgumentException("Missing configuration setting: " + name);
        String value = m.group(1).strip();
        if (value.startsWith("\"") && value.endsWith("\"")) return value.substring(1, value.length() - 1);
        if (value.startsWith("'") && value.endsWith("'")) return value.substring(1, value.length() - 1);
        return value;
    }

    private static int version(Path p) {
        String name = p.getFileName().toString();
        return Integer.parseInt(name.substring(1, name.indexOf("__")));
    }

    private static void runScenario(Connection admin, String url, Properties credentials,
                                    List<Path> versions, boolean upgrade) throws Exception {
        String db = "spoken_schema_verify_" + UUID.randomUUID().toString().replace("-", "");
        if (!db.matches("spoken_schema_verify_[0-9a-f]{32}")) throw new IllegalStateException("Unsafe test schema");
        execute(admin, "CREATE DATABASE `" + db + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        try (Connection c = DriverManager.getConnection(url, credentials)) {
            c.setCatalog(db);
            execute(c, "SET SESSION sql_mode = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION'");
            for (Path p : versions) {
                if (upgrade && version(p) == 9) seedV8(c);
                if (version(p) == 10) {
                    if (upgrade) verifyUpgrade(c);
                    regressionCases(c);
                    execute(c, "INSERT INTO conversation_turn(session_id,seq,user_text,created_at) VALUES ('datetime-fixture',0,'legacy',1700000000123)");
                    execute(c, "UPDATE app_user SET created_at=1700000000123,updated_at=1700000200456 WHERE id=1");
                }
                for (String sql : statements(Files.readString(p))) execute(c, sql);
            }
            check(scalar(c, "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE()") == 19,
                    "19 business tables");
            verifyAuditDates(c);
            verifyMyBatis(c, versions.get(0).getParent().getParent().getParent());
            System.out.println("PASS: " + (upgrade ? "V8 data upgrade through V10" : "fresh V1-V10 installation"));
        } finally {
            // Only remove the exact schema created by this invocation, never a user-supplied name.
            execute(admin, "DROP DATABASE `" + db + "`");
        }
    }

    private static void verifyAuditDates(Connection c) throws SQLException {
        check(scalar(c, "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND column_name IN ('created_at','updated_at') AND data_type='datetime' AND is_nullable='NO' AND column_default='CURRENT_TIMESTAMP'") == 38,
                "all 19 tables have mandatory DATETIME audit defaults");
        check(scalar(c, "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND column_name='updated_at' AND LOWER(extra) LIKE '%on update current_timestamp%'") == 19,
                "all updated_at columns automatically refresh");
        check(scalar(c, "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND column_name IN ('created_at_datetime','updated_at_datetime')") == 0,
                "no migration shadow columns remain");
        check(scalar(c, "SELECT COUNT(*) FROM conversation_turn WHERE session_id='datetime-fixture' AND created_at='2023-11-15 06:13:20' AND updated_at=created_at") == 1,
                "epoch millis correctly convert to Shanghai seconds");
        check(scalar(c, "SELECT COUNT(*) FROM app_user WHERE id=1 AND created_at='2023-11-15 06:13:20' AND updated_at='2023-11-15 06:16:40'") == 1,
                "original creation and update instants preserved");
        check(scalar(c, "SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND column_name='created_at' AND index_name IN ('idx_pa_session','idx_pa_retention','idx_msg_user','idx_msg_retention','idx_summary_user','idx_summary_retention','idx_finalize_user','idx_login_user','idx_login_time')") == 9,
                "all time indexes survive column replacement");
        execute(c, "INSERT INTO app_user(id,user_no) VALUES (3,'database-defaults')");
        check(scalar(c, "SELECT COUNT(*) FROM app_user WHERE id=3 AND created_at IS NOT NULL AND updated_at=created_at") == 1,
                "omitted audit fields use database defaults");
        execute(c, "UPDATE app_user SET updated_at='2000-01-01 00:00:00' WHERE id=3");
        execute(c, "UPDATE app_user SET nickname='automatic-update' WHERE id=3");
        check(scalar(c, "SELECT COUNT(*) FROM app_user WHERE id=3 AND updated_at>'2000-01-01 00:00:00' AND created_at IS NOT NULL") == 1,
                "ordinary changes automatically refresh update time");
        check(DatabaseTime.toEpochMillis(LocalDateTime.of(2023,11,15,6,13,20)) == 1700000000000L,
                "domain conversion does not depend on JVM timezone");
    }

    private static void verifyMyBatis(Connection c, Path resources) throws Exception {
        Configuration configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        for (String file : List.of("TrainingSessionMapper.xml", "ConversationTurnMapper.xml", "PronunciationAssessmentMapper.xml")) {
            Path mapper = resources.resolve("mybatis/mapper").resolve(file);
            try (InputStream stream = Files.newInputStream(mapper)) {
                new XMLMapperBuilder(stream, configuration, mapper.toString(), configuration.getSqlFragments()).parse();
            }
        }
        try (SqlSession session = new SqlSessionFactoryBuilder().build(configuration).openSession(c)) {
            ITrainingSessionDao sessions = session.getMapper(ITrainingSessionDao.class);
            TrainingSessionPO storedSession = new TrainingSessionPO();
            storedSession.setId("mybatis");
            storedSession.setDeviceId("test-device");
            storedSession.setStatus("READY");
            storedSession.setStartedAt(1700000000123L);
            sessions.insert(storedSession);
            TrainingSessionPO readSession = sessions.selectById("mybatis");
            check(readSession.getCreatedAt() != null && readSession.getUpdatedAt() != null, "session DATETIME mapping");
            check(readSession.getStartedAt() == 1700000000123L, "business event timestamp unchanged");

            IConversationTurnDao turns = session.getMapper(IConversationTurnDao.class);
            ConversationTurnPO turn = new ConversationTurnPO();
            turn.setSessionId("mybatis");
            turn.setSeq(0);
            turn.setUserText("hello");
            turns.insert(turn);
            ConversationTurnPO readTurn = turns.selectById(turn.getId());
            check(readTurn.getCreatedAt() != null && readTurn.getUpdatedAt() != null, "turn DATETIME mapping and defaults");
            execute(c, "UPDATE conversation_turn SET updated_at='2000-01-01 00:00:00' WHERE id=" + turn.getId());
            turn.setAssistantText("Hi!");
            turns.updateAssistant(turn);
            session.clearCache();
            check(turns.selectById(turn.getId()).getUpdatedAt().getYear() > 2000, "turn mapper auto-update time");

            IPronunciationAssessmentDao assessments = session.getMapper(IPronunciationAssessmentDao.class);
            PronunciationAssessmentPO assessment = new PronunciationAssessmentPO();
            assessment.setSessionId("mybatis");
            assessment.setTurnId(turn.getId());
            assessment.setScore(80.0);
            assessments.insert(assessment);
            PronunciationAssessmentPO readAssessment = assessments.selectById(assessment.getId());
            check(readAssessment.getCreatedAt() != null && readAssessment.getUpdatedAt() != null,
                    "assessment DATETIME mapping and defaults");
            check(assessments.selectBySessionIdDesc("mybatis").size() == 1, "assessment ordering on DATETIME");
            session.commit();
        }
    }

    private static void seedV8(Connection c) throws SQLException {
        execute(c, "INSERT INTO app_user(id,user_no) VALUES (900,'legacy')");
        execute(c, "INSERT INTO training_session(id,started_at,ended_at) VALUES ('legacy',1000,2000)");
        execute(c, "INSERT INTO conversation_turn(session_id,seq,user_text) VALUES ('legacy',0,'still used')");
        execute(c, "INSERT INTO user_identity(user_id,provider,provider_uid,deleted_at) VALUES (900,'phone','retired',1000)");
        execute(c, "INSERT INTO user_profile_item(user_id,item_key,item_value) VALUES (900,'interest','legacy')");
        execute(c, "INSERT INTO conversation_message(session_id,user_id,seq,role) VALUES ('legacy',900,7,'user')");
        execute(c, "INSERT INTO conversation_summary(session_id,user_id,avg_score) VALUES ('legacy',900,80)");
        execute(c, "INSERT INTO user_learning_stat(user_id,assessed_count,avg_score,long_term_level) VALUES (900,2,80,'LOW')");
    }

    private static void verifyUpgrade(Connection c) throws SQLException {
        check(scalar(c, "SELECT next_message_seq FROM training_session WHERE id='legacy'") == 8, "sequence backfill");
        check(scalar(c, "SELECT created_at FROM conversation_message WHERE session_id='legacy'") == 1000, "message time backfill");
        check(scalar(c, "SELECT created_at FROM conversation_summary WHERE session_id='legacy'") == 2000, "summary time backfill");
        check(scalar(c, "SELECT COUNT(*) FROM user_learning_stat WHERE user_id=900 AND score_sum IS NULL AND level_algorithm_version IS NULL") == 1,
                "legacy rounded scores and unversioned level remain unknown");
        check(scalar(c, "SELECT COUNT(*) FROM conversation_turn WHERE session_id='legacy'") == 1, "legacy turn preserved");
        check(scalar(c, "SELECT COUNT(*) FROM conversation_message WHERE session_id='legacy' AND idempotency_key IS NULL") == 1,
                "legacy message compatibility");
        execute(c, "INSERT INTO user_identity(user_id,provider,provider_uid) VALUES (901,'phone','retired')");
        check(scalar(c, "SELECT COUNT(*) FROM user_identity WHERE provider_uid='retired'") == 2, "retired identity can rebind");
        execute(c, "INSERT INTO user_profile_item(user_id,item_key,item_value) VALUES (900,'interest',CONCAT(REPEAT('a',191),'x'))");
        execute(c, "INSERT INTO user_profile_item(user_id,item_key,item_value) VALUES (900,'interest',CONCAT(REPEAT('a',191),'y'))");
        check(scalar(c, "SELECT COUNT(*) FROM user_profile_item WHERE user_id=900") == 3, "long-value collision fixed on upgrade");
    }

    private static void regressionCases(Connection c) throws SQLException {
        execute(c, "INSERT INTO app_user(id,user_no) VALUES (1,'test-one'),(2,'test-two')");
        execute(c, "INSERT INTO training_session(id,user_id,started_at) VALUES ('test',1,1000),('other',2,1000)");
        execute(c, "INSERT INTO user_profile_item(user_id,item_key,item_value) VALUES (1,'persona_note',CONCAT(REPEAT('a',511),'x'))");
        execute(c, "INSERT INTO user_profile_item(user_id,item_key,item_value) VALUES (1,'persona_note',CONCAT(REPEAT('a',511),'y'))");
        expectError(c, "INSERT INTO user_profile_item(user_id,item_key,item_value) VALUES (1,'persona_note',CONCAT(REPEAT('a',511),'x'))", 1062);
        execute(c, "INSERT INTO user_profile_item(user_id,item_key,item_value) VALUES (2,'persona_note',CONCAT(REPEAT('a',511),'x'))");
        expectError(c, "UPDATE user_profile_item SET confidence=1.5 WHERE user_id=1", 3819);
        expectError(c, "UPDATE user_profile_item SET status='rejected' WHERE user_id=1", 3819);
        execute(c, "UPDATE user_profile_item SET status='rejected',rejected_at=2000 WHERE user_id=1");

        execute(c, "INSERT INTO user_identity(user_id,provider,provider_scope,provider_uid) VALUES (1,'wechat','app-a','AbC')");
        expectError(c, "INSERT INTO user_identity(user_id,provider,provider_scope,provider_uid) VALUES (2,'wechat','app-a','AbC')", 1062);
        execute(c, "INSERT INTO user_identity(user_id,provider,provider_scope,provider_uid) VALUES (2,'wechat','app-a','abc'),(2,'wechat','app-b','AbC')");
        execute(c, "UPDATE user_identity SET deleted_at=2000 WHERE provider_scope='app-a' AND provider_uid='AbC'");
        execute(c, "INSERT INTO user_identity(user_id,provider,provider_scope,provider_uid) VALUES (2,'wechat','app-a','AbC')");
        execute(c, "UPDATE user_identity SET deleted_at=3000 WHERE deleted_at IS NULL AND provider_scope='app-a' AND provider_uid='AbC'");
        execute(c, "INSERT INTO user_identity(user_id,provider,provider_scope,provider_uid) VALUES (1,'wechat','app-a','AbC')");
        check(scalar(c, "SELECT COUNT(*) FROM user_identity WHERE provider_scope='app-a' AND provider_uid='AbC'") == 3,
                "multiple retired histories plus one active binding");
        expectError(c, "UPDATE user_identity SET deleted_at=NULL WHERE deleted_at IS NOT NULL", 1062);

        execute(c, "INSERT INTO conversation_message(session_id,user_id,seq,role,created_at,idempotency_key) VALUES ('test',1,0,'user',1000,'request:one')");
        expectError(c, "INSERT INTO conversation_message(session_id,user_id,seq,role,created_at) VALUES ('test',1,0,'assistant',1000)", 1062);
        expectError(c, "INSERT INTO conversation_message(session_id,user_id,seq,role,created_at,idempotency_key) VALUES ('test',1,1,'user',1000,'request:one')", 1062);
        execute(c, "INSERT INTO conversation_message(session_id,user_id,seq,role,created_at,idempotency_key) VALUES ('other',2,0,'user',1000,'request:one')");
        expectError(c, "INSERT INTO conversation_message(session_id,user_id,seq,role) VALUES ('test',1,1,'user')", 1364);
        expectError(c, "INSERT INTO conversation_message(session_id,user_id,seq,role,created_at) VALUES ('test',1,1,'invalid',1000)", 3819);
        expectError(c, "INSERT INTO conversation_message(session_id,user_id,seq,role,created_at) VALUES ('test',1,-1,'user',1000)", 3819);
        expectError(c, "INSERT INTO conversation_message(session_id,user_id,seq,role,created_at,audio_ms) VALUES ('test',1,1,'user',1000,-1)", 3819);

        execute(c, "INSERT INTO conversation_summary(session_id,user_id,created_at,level_signal_json) VALUES ('test',1,2000,'{\"newField\":true}')");
        expectError(c, "UPDATE conversation_summary SET assessed_count=1,score_sum=101 WHERE session_id='test'", 3819);
        expectError(c, "UPDATE conversation_summary SET level_low_count=-1 WHERE session_id='test'", 3819);
        execute(c, "INSERT INTO user_profile(user_id,preferred_voice) VALUES (1,'Cherry')");
        check(scalar(c, "SELECT COUNT(*) FROM user_profile WHERE user_id=1 AND preferred_tts_provider IS NULL AND practice_timezone='Asia/Shanghai'") == 1,
                "legacy voice preference preserves unknown provider");

        execute(c, "INSERT INTO user_learning_stat(user_id) VALUES (1)");
        execute(c, "INSERT INTO conversation_finalize_task(session_id,user_id,next_retry_at,created_at,updated_at) VALUES ('test',1,1000,1000,1000)");
        expectError(c, "INSERT INTO conversation_finalize_task(session_id,user_id,next_retry_at,created_at,updated_at) VALUES ('test',1,1000,1000,1000)", 1062);
        expectError(c, "UPDATE conversation_finalize_task SET status='running' WHERE session_id='test'", 3819);
        expectError(c, "UPDATE conversation_finalize_task SET status='completed' WHERE session_id='test'", 3819);
        execute(c, "UPDATE conversation_finalize_task SET status='running',lease_token='new',lease_until=999999 WHERE session_id='test'");
        check(update(c, "UPDATE conversation_finalize_task SET status='failed' WHERE session_id='test' AND lease_token='old'") == 0,
                "stale worker fencing token");
        c.setAutoCommit(false);
        execute(c, "UPDATE user_learning_stat SET total_sessions=total_sessions+1 WHERE user_id=1");
        execute(c, "UPDATE conversation_finalize_task SET status='completed',completed_at=2000 WHERE session_id='test'");
        c.rollback();
        c.setAutoCommit(true);
        check(scalar(c, "SELECT total_sessions FROM user_learning_stat WHERE user_id=1") == 0, "stat effects roll back");
        check(scalar(c, "SELECT COUNT(*) FROM conversation_finalize_task WHERE session_id='test' AND status='running'") == 1,
                "completion marker rolls back with statistics");
        c.setAutoCommit(false);
        execute(c, "UPDATE user_learning_stat SET total_sessions=total_sessions+1 WHERE user_id=1");
        execute(c, "UPDATE conversation_finalize_task SET status='completed',completed_at=2000 WHERE session_id='test'");
        c.commit();
        c.setAutoCommit(true);
        check(update(c, "UPDATE conversation_finalize_task SET status='running' WHERE session_id='test' AND status IN ('pending','failed')") == 0,
                "completed task cannot be reclaimed by claim query");

        execute(c, "INSERT INTO user_learning_day(user_id,practice_date,timezone,session_count,created_at,updated_at) VALUES (1,'2026-10-12','Asia/Shanghai',1,1000,1000)");
        expectError(c, "INSERT INTO user_learning_day(user_id,practice_date,timezone,created_at,updated_at) VALUES (1,'2026-10-12','Asia/Shanghai',1000,1000)", 1062);
        execute(c, "INSERT INTO user_practice_plan(user_id,title) VALUES (1,'test plan')");
        execute(c, "INSERT INTO user_practice_plan_day(plan_id,user_id,practice_date,created_at) VALUES (1,1,'2026-10-12',1000)");
        expectError(c, "INSERT INTO user_practice_plan_day(plan_id,user_id,practice_date,created_at) VALUES (1,1,'2026-10-12',1000)", 1062);
        check(scalar(c, "SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND index_name IN ('idx_msg_retention','idx_summary_retention','idx_pa_retention','idx_item_due') AND seq_in_index=1") == 4,
                "retention and due-date indexes present");
    }

    private static void execute(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement()) { s.execute(sql); }
    }

    private static int update(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement()) { return s.executeUpdate(sql); }
    }

    private static long scalar(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) {
            if (!r.next()) throw new AssertionError("Scalar query returned no row");
            return r.getLong(1);
        }
    }

    private static void expectError(Connection c, String sql, int error) throws SQLException {
        try {
            execute(c, sql);
        } catch (SQLException e) {
            if (e.getErrorCode() != error) throw e;
            assertions++;
            return;
        }
        throw new AssertionError("Expected MySQL error " + error + ": " + sql);
    }

    private static void check(boolean result, String label) {
        if (!result) throw new AssertionError(label);
        assertions++;
    }

    // Quote-aware splitter for these migrations. Preserves semicolons inside SQL strings,
    // doubled quotes and escaped characters, and ignores -- comments only outside strings.
    private static List<String> statements(String script) {
        List<String> result = new ArrayList<>();
        StringBuilder sql = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < script.length(); i++) {
            char ch = script.charAt(i);
            if (quote != 0) {
                sql.append(ch);
                if (ch == '\\' && i + 1 < script.length()) sql.append(script.charAt(++i));
                else if (ch == quote) {
                    if (i + 1 < script.length() && script.charAt(i + 1) == quote) sql.append(script.charAt(++i));
                    else quote = 0;
                }
            } else if (ch == '-' && i + 1 < script.length() && script.charAt(i + 1) == '-') {
                while (i + 1 < script.length() && script.charAt(i + 1) != '\n') i++;
                sql.append('\n');
            } else if (ch == '\'' || ch == '"' || ch == '`') {
                quote = ch;
                sql.append(ch);
            } else if (ch == ';') {
                if (!sql.toString().isBlank()) result.add(sql.toString().strip());
                sql.setLength(0);
            } else sql.append(ch);
        }
        if (quote != 0) throw new IllegalArgumentException("Unterminated SQL quote");
        if (!sql.toString().isBlank()) result.add(sql.toString().strip());
        return result;
    }
}

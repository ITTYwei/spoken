package cn.adinall.ai.architecture;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map.Entry;
import java.util.stream.Stream;

import org.junit.Test;
import java.nio.file.*;
import java.util.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import static org.junit.Assert.*;

/**
 * <b>功能</b>：防止领域再次依赖外层或模型 SDK，并核验迁移后的 MyBatis 类型引用。
 * <p><b>体系架构 / 运行过程</b></p>
 * <pre>source imports -> layer dependency checks
mapper resources -> MyBatis XML parser -> mapped statement validation</pre>
 * <p><b>案例</b>：Domain 新增 ADK Event 导入 -> 测试失败；Mapper 仍引用旧包 PO -> 解析失败。</p>
 * <b>设计要点</b>
 * <ul><li>覆盖本次大规模包迁移的真实风险。</li><li>不连接数据库，不访问在线模型。</li></ul>
 */
public class ArchitectureBoundaryTest {
    /** 会话与教学通过 Case 协调，领域包不能重新互相持有对方的状态。 */
    @Test public void conversationAndTeachingRemainSeparateSubdomains() throws Exception {
        Path coaching = root().resolve("spoken-server-domain/src/main/java/cn/adinall/ai/domain/coaching");
        Map<String, String> forbidden = Map.of(
                "conversation", "cn.adinall.ai.domain.coaching.teaching.",
                "teaching", "cn.adinall.ai.domain.coaching.conversation.");
        for (Entry<String, String> boundary : forbidden.entrySet()) {
            try (Stream<Path> files = Files.walk(coaching.resolve(boundary.getKey()))) {
                for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                    assertFalse(file.toString(), Files.readString(file).contains(boundary.getValue()));
                }
            }
        }
    }

    private Path root() {
        Path path = Paths.get("").toAbsolutePath();
        while (path != null && !Files.isDirectory(path.resolve("spoken-server-domain"))) path=path.getParent();
        assertNotNull(path); return path;
    }
    @Test public void dependencyDirectionRemainsInward() throws Exception {
        Map<String,List<String>> forbidden = Map.of(
                "domain", List.of("cn.adinall.ai.infrastructure.","cn.adinall.ai.cases.","cn.adinall.ai.trigger.",
                        "cn.adinall.ai.api.","com.google.adk.","org.springframework.ai.","io.reactivex."),
                "case", List.of("cn.adinall.ai.infrastructure.","cn.adinall.ai.trigger.","cn.adinall.ai.api.","cn.adinall.ai.domain.identity.support.AccountContext"),
                "infrastructure", List.of("cn.adinall.ai.cases.","cn.adinall.ai.trigger.","cn.adinall.ai.api."));
        for (Entry<String, List<String>> module : forbidden.entrySet()) {
            try (Stream<Path> files = Files.walk(root().resolve("spoken-server-"+module.getKey()+"/src/main/java"))) {
                for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                    for (String line : Files.readAllLines(file)) {
                        for (String prefix : module.getValue()) assertFalse(file+": "+line, line.contains(prefix));
                    }
                }
            }
        }
    }
    @Test public void applicationDependenciesCannotSilentlyDisappearOrHideCycles() throws Exception {
        Path source=root().resolve("spoken-server-case/src/main/java");
        try(Stream<Path> files=Files.walk(source)) {
            for(Path file:files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String text=Files.readString(file);
                assertFalse(file.toString(),text.contains("required = false") || text.contains("required=false"));
                assertFalse(file.toString(),text.contains("@Lazy") || text.contains("@org.springframework.context.annotation.Lazy"));
            }
        }
        assertFalse(Files.readString(root().resolve("spoken-server-case/pom.xml")).contains("<artifactId>spoken-server-api</artifactId>"));
    }
    @Test public void allMyBatisMappersResolveAfterPackageMoves() throws Exception {
        Configuration configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        Path directory = root().resolve("spoken-server-app/src/main/resources/mybatis/mapper");
        try (Stream<Path> files = Files.list(directory)) {
            for (Path path : files.filter(p -> p.toString().endsWith(".xml")).toList()) {
                try (InputStream input=Files.newInputStream(path)) {
                    new XMLMapperBuilder(input, configuration, path.toString(), configuration.getSqlFragments()).parse();
                }
            }
        }
        assertTrue(configuration.hasStatement("cn.adinall.ai.infrastructure.dao.IConversationHistoryDao.turns"));
    }
}

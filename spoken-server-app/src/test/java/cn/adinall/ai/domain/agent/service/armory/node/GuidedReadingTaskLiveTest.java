package cn.adinall.ai.domain.agent.service.armory.node;

import cn.adinall.ai.cases.conversation.TeachingTestFixture;
import cn.adinall.ai.domain.agent.model.valobj.properties.AiAgentAutoConfigProperties;
import cn.adinall.ai.domain.agent.service.armory.factory.DefaultArmoryFactory;
import cn.adinall.ai.domain.agent.service.chat.ChatService;
import cn.adinall.ai.domain.agent.service.context.ChatContextService;
import cn.adinall.ai.domain.conversation.model.aggregate.ConversationSessionAggregate;
import cn.adinall.ai.domain.conversation.model.valobj.*;
import cn.adinall.ai.domain.conversation.service.reply.GuidedReadingPolicy;
import cn.adinall.ai.domain.conversation.service.reply.TeachingReplyGuard;
import cn.adinall.ai.domain.learner.model.valobj.EnglishLevelEnum;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/**
 * 当前配置模型的情境跟读在线回归，专门检查按钮没有用户原话时能否执行可信任务。
 * 沿真实 YAML、装配节点、ChatService、ADK 短会话和业务护栏调用，不另建模型客户端。
 * 存储及身份端口使用替身，不访问用户数据库、数字人或评测服务；不写用户聊天记录。
 * 默认跳过，显式 spoken.verify.live-reading=true 后才发送无个人数据的合成练习请求。
 */
public class GuidedReadingTaskLiveTest {
    /** 验证 L1/L2 首句和 L2 通过后新句；新句必须实际经过业务护栏且不重复近期目标。 */
    @Test(timeout = 240_000)
    public void configuredModelGeneratesInitialAndNextReadingWithEmptyButtonInput() throws Exception {
        assumeTrue("在线跟读验证需显式开启", Boolean.getBoolean("spoken.verify.live-reading"));
        assertNotNull("需配置模型鉴权环境变量", System.getenv("LK888_API_KEY"));
        var environment = new StandardEnvironment();
        for (var source : new YamlPropertySourceLoader().load("reading-task-check", new ClassPathResource("agent/spoken-server.yml")))
            environment.getPropertySources().addLast(source);
        var properties = Binder.get(environment).bind("ai.agent.config", AiAgentAutoConfigProperties.class).get();
        var table = properties.getTables().values().stream().filter(t -> "spoken-server".equals(t.getAppName())).findFirst().orElseThrow();
        // 仅隔离外部工具和工作流，保留配置中的模型、身份 instruction 及 turn_policy 占位符。
        table.getModule().getChatModel().setLogLlmPayload(false);
        table.getModule().getChatModel().setToolMcpList(List.of());
        table.getModule().getChatModel().setToolSkillsList(List.of());
        table.getModule().setAgentWorkflows(List.of());
        var reports = new ArrayList<Map<String,Object>>();
        try (var container = new GenericApplicationContext()) {
            container.refresh();
            new AgentReadOnlyToolLiveTest().assemble(table, container);
            var factory = new DefaultArmoryFactory();
            ReflectionTestUtils.setField(factory, "applicationContext", container);
            var chat = new ChatService();
            ReflectionTestUtils.setField(chat, "defaultArmoryFactory", factory);
            for (var level : List.of(EnglishLevelEnum.L1, EnglishLevelEnum.L2)) {
                var teaching = TeachingTestFixture.create();
                ReflectionTestUtils.setField(teaching, "context", new ChatContextService(List.of(),12000));
                var session = new ConversationSessionAggregate("reading-live-"+level, "test-provider", "test-device", null,null,null,"free",SessionStatusEnum.ACTIVE);
                session.teaching().initialize(level);
                session.teaching().guidedReading(true);
                var initial = new TeachingDecisionVO(level, new TeachingTaskVO("READ", "", "", 0), TeachingOutcomeEnum.RESPONDED,true,"",false,true);
                for (int step=0; step<(level==EnglishLevelEnum.L2 ? 2 : 1); step++) {
                    var decision = step==0 ? initial : new TeachingDecisionVO(level, new TeachingTaskVO("READ", "", "", 0),
                            TeachingOutcomeEnum.READ_PASS,true,"",true,true,session.teaching().pending(),java.time.Instant.now());
                    String input = step==0 ? "" : session.teaching().pending().target();
                    var regenerated = new AtomicBoolean();
                    long started = System.nanoTime();
                    // 每次重生成重新组装 SYSTEM 和预算；空按钮原话不伪造成人类学习请求。
                    Flux<String> source = Flux.defer(() -> chat.handlePreparedTextStream(table.getAgent().getAgentId(), session.getSessionId(),
                            teaching.prompt(session,input,decision,null,regenerated.get()),teaching.systemPolicy(session,decision,regenerated.get())));
                    String spoken = teaching.guard(session, decision, TeachingReplyGuard.apply(source,decision), () -> regenerated.set(true))
                            .collectList().map(parts -> String.join("",parts)).block(Duration.ofSeconds(65));
                    assertNotNull(spoken);
                    session.teaching().commitSpoken(decision, spoken);
                    assertTrue("应提取模型实际邀请的英文目标", session.teaching().pending().reading());
                    if(step>0) assertFalse(GuidedReadingPolicy.sameTarget(session.teaching().recentReadingTargets().get(0), session.teaching().pending().target()));
                    reports.add(Map.of("level",level.name(),"step",step,"target",session.teaching().pending().target(),"spoken",spoken,
                            "regenerated",regenerated.get(),"elapsedMs",Duration.ofNanos(System.nanoTime()-started).toMillis()));
                }
            }
        }
        Path report = Path.of("target","guided-reading-task-live-report.json");
        Files.createDirectories(report.getParent());
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(report.toFile(),Map.of("model",table.getModule().getChatModel().getModel(),"results",reports));
    }
}

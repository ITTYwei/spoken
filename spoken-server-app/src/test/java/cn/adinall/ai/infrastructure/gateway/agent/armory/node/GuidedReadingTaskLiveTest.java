package cn.adinall.ai.infrastructure.gateway.agent.armory.node;

import cn.adinall.ai.domain.coaching.teaching.service.TeachingService;
import cn.adinall.ai.cases.teaching.TeachingPreparation;
import cn.adinall.ai.domain.coaching.teaching.model.valobj.TeachingDecisionVO;
import cn.adinall.ai.infrastructure.config.agent.AiAgentConfigTableVO;
import java.util.ArrayList;
import java.util.Map;
import org.springframework.core.env.PropertySource;

import cn.adinall.ai.cases.conversation.TeachingTestFixture;
import cn.adinall.ai.infrastructure.config.agent.AiAgentAutoConfigProperties;
import cn.adinall.ai.infrastructure.gateway.agent.armory.factory.DefaultArmoryFactory;
import cn.adinall.ai.infrastructure.gateway.agent.chat.ChatService;
import cn.adinall.ai.domain.companion.service.context.ChatContextService;
import cn.adinall.ai.domain.coaching.conversation.model.aggregate.ConversationSessionAggregate;
import cn.adinall.ai.domain.coaching.conversation.model.valobj.*;
import cn.adinall.ai.domain.coaching.teaching.model.valobj.TeachingOutcomeEnum;
import cn.adinall.ai.domain.coaching.teaching.model.valobj.TeachingTaskVO;
import cn.adinall.ai.domain.coaching.teaching.service.reply.GuidedReadingPolicy;
import cn.adinall.ai.domain.coaching.teaching.service.reply.TeachingReplyGuard;
import cn.adinall.ai.domain.learning.model.valobj.EnglishLevelEnum;
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
    /** 每个测试独立持有教学运行态，并显式共享给相关应用协作者。 */
    private final TeachingService teachingStates = new TeachingService();

    /** 验证 L1/L2 首句和 L2 通过后新句；新句必须实际经过业务护栏且不重复近期目标。 */
    @Test(timeout = 240_000)
    public void configuredModelGeneratesInitialAndNextReadingWithEmptyButtonInput() throws Exception {
        assumeTrue("在线跟读验证需显式开启", Boolean.getBoolean("spoken.verify.live-reading"));
        assertNotNull("需配置模型鉴权环境变量", System.getenv("LK888_API_KEY"));
        StandardEnvironment environment = new StandardEnvironment();
        for (PropertySource<?> source : new YamlPropertySourceLoader().load("reading-task-check", new ClassPathResource("agent/spoken-server.yml")))
            environment.getPropertySources().addLast(source);
        AiAgentAutoConfigProperties properties = Binder.get(environment).bind("ai.agent.config", AiAgentAutoConfigProperties.class).get();
        AiAgentConfigTableVO table = properties.getTables().values().stream().filter(t -> "spoken-server".equals(t.getAppName())).findFirst().orElseThrow();
        // 仅隔离外部工具和工作流，保留配置中的模型、身份 instruction 及 turn_policy 占位符。
        table.getModule().getChatModel().setLogLlmPayload(false);
        table.getModule().getChatModel().setToolMcpList(List.of());
        table.getModule().getChatModel().setToolSkillsList(List.of());
        table.getModule().setAgentWorkflows(List.of());
        ArrayList<Map<String, Object>> reports = new ArrayList<Map<String,Object>>();
        try (GenericApplicationContext container = new GenericApplicationContext()) {
            container.refresh();
            new AgentReadOnlyToolLiveTest().assemble(table, container);
            DefaultArmoryFactory factory = new DefaultArmoryFactory();
            ReflectionTestUtils.setField(factory, "applicationContext", container);
            ChatService chat = new ChatService();
            ReflectionTestUtils.setField(chat, "defaultArmoryFactory", factory);
            for (EnglishLevelEnum level : List.of(EnglishLevelEnum.L1, EnglishLevelEnum.L2)) {
                TeachingPreparation teaching = TeachingTestFixture.create(teachingStates);
                ReflectionTestUtils.setField(teaching, "context", new ChatContextService(List.of(),12000));
                ConversationSessionAggregate session = new ConversationSessionAggregate("reading-live-"+level, "test-provider", "test-device", null,null,null,"free",SessionStatusEnum.ACTIVE);
                teachingStates.state(session.getSessionId()).initialize(level);
                teachingStates.state(session.getSessionId()).guidedReading(true);
                TeachingDecisionVO initial = new TeachingDecisionVO(level, new TeachingTaskVO("READ", "", "", 0), TeachingOutcomeEnum.RESPONDED,true,"",false,true);
                for (int step=0; step<(level==EnglishLevelEnum.L2 ? 2 : 1); step++) {
                    TeachingDecisionVO decision = step==0 ? initial : new TeachingDecisionVO(level, new TeachingTaskVO("READ", "", "", 0),
                            TeachingOutcomeEnum.READ_PASS,true,"",true,true,teachingStates.state(session.getSessionId()).pending(),java.time.Instant.now());
                    String input = step==0 ? "" : teachingStates.state(session.getSessionId()).pending().target();
                    AtomicBoolean regenerated = new AtomicBoolean();
                    long started = System.nanoTime();
                    // 每次重生成重新组装 SYSTEM 和预算；空按钮原话不伪造成人类学习请求。
                    Flux<String> source = Flux.defer(() -> chat.handlePreparedTextStream(table.getAgent().getAgentId(), session.getSessionId(),
                            teaching.prompt(session,input,decision,null,regenerated.get()),teaching.systemPolicy(session,decision,regenerated.get())));
                    String spoken = teaching.guard(session, decision, TeachingReplyGuard.apply(source,decision), () -> regenerated.set(true))
                            .collectList().map(parts -> String.join("",parts)).block(Duration.ofSeconds(65));
                    assertNotNull(spoken);
                    teachingStates.state(session.getSessionId()).commitSpoken(decision, spoken);
                    assertTrue("应提取模型实际邀请的英文目标", teachingStates.state(session.getSessionId()).pending().reading());
                    if(step>0) assertFalse(GuidedReadingPolicy.sameTarget(teachingStates.state(session.getSessionId()).recentReadingTargets().get(0), teachingStates.state(session.getSessionId()).pending().target()));
                    reports.add(Map.of("level",level.name(),"step",step,"target",teachingStates.state(session.getSessionId()).pending().target(),"spoken",spoken,
                            "regenerated",regenerated.get(),"elapsedMs",Duration.ofNanos(System.nanoTime()-started).toMillis()));
                }
            }
        }
        Path report = Path.of("target","guided-reading-task-live-report.json");
        Files.createDirectories(report.getParent());
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(report.toFile(),Map.of("model",table.getModule().getChatModel().getModel(),"results",reports));
    }
}

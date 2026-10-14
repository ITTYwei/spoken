package cn.adinall.ai.domain.agent.service.armory.node;

import cn.adinall.ai.cases.practice.*;
import cn.adinall.ai.domain.agent.model.valobj.*;
import cn.adinall.ai.domain.agent.model.valobj.properties.AiAgentAutoConfigProperties;
import cn.adinall.ai.domain.agent.service.chat.ChatService;
import cn.adinall.ai.domain.agent.service.armory.factory.DefaultArmoryFactory;
import cn.adinall.ai.domain.agent.service.ILongTermMemoryService;
import cn.adinall.ai.domain.account.service.*;
import cn.adinall.ai.domain.conversation.service.IConversationService;
import cn.adinall.ai.domain.conversation.adapter.port.*;
import cn.adinall.ai.domain.conversation.model.aggregate.ConversationSessionAggregate;
import cn.adinall.ai.domain.conversation.model.valobj.SessionStatusEnum;
import cn.adinall.ai.domain.practice.adapter.repository.IRecentLessonRepository;
import cn.adinall.ai.domain.practice.service.PracticeService;
import cn.adinall.ai.domain.scenario.service.TextbookService;
import cn.adinall.ai.infrastructure.adapter.repository.ResourceTextbookRepository;
import cn.adinall.ai.trigger.tool.SpokenPracticeTools;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.util.ReflectionTestUtils;
import org.junit.Test;
import java.time.Duration;
import java.util.*;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;
import static org.mockito.Mockito.*;

/**
 * 显式启用的在线验收：真实现有 YAML/装配节点/ADK Runner 调用实际课程工具和可信上下文，
 * 持久化使用隔离替身，避免改用户配置。验证模型确实调用工具，而非只在回复里声称完成。
 */
public class AgentCourseToolLiveTest {
    /** 课程选择、自由模式和长期目标三轮分别创建短 ADK 会话，业务状态仍来自后端。 */
    @Test public void configuredModelExecutesTrustedBusinessTools() throws Exception {
        assumeTrue(Boolean.getBoolean("spoken.verify.live-tools"));
        var environment=new StandardEnvironment();
        for(var source:new YamlPropertySourceLoader().load("course-live",new ClassPathResource("agent/spoken-server.yml"))) environment.getPropertySources().addLast(source);
        var properties=Binder.get(environment).bind("ai.agent.config",AiAgentAutoConfigProperties.class).get();
        var table=properties.getTables().values().stream().filter(v->"spoken-server".equals(v.getAppName())).findFirst().orElseThrow();
        table.getModule().getChatModel().setLogLlmPayload(false);
        var session=new ConversationSessionAggregate("live-business","p","d","a",null,"voice","free",SessionStatusEnum.READY);
        session.bindTeachingSubject("user:7");
        var conversations=mock(IConversationService.class); when(conversations.requireSession("live-business")).thenReturn(session);
        var practice=new PracticeService(mock(IRecentLessonRepository.class));
        var books=new TextbookService(new ResourceTextbookRepository(new ObjectMapper()));
        var events=mock(IConversationEventBusPort.class); var courses=new PracticeCase(conversations,practice,books,events);
        var profiles=mock(IProfileService.class);
        var actions=new AgentActionCase(conversations,courses,practice,new AgentActionGuard(practice),profiles,mock(IHomeService.class),mock(IAvatarDriverPort.class),mock(ILongTermMemoryService.class),events);
        var tools=new SpokenPracticeTools(actions);
        try(var container=new GenericApplicationContext()) {
            container.registerBean("spokenPracticeTools",org.springframework.ai.tool.ToolCallbackProvider.class,
                    ()->MethodToolCallbackProvider.builder().toolObjects(tools).build());
            container.refresh(); new AgentReadOnlyToolLiveTest().assemble(table,container);
            var factory=new DefaultArmoryFactory(); ReflectionTestUtils.setField(factory,"applicationContext",container);
            var chat=new ChatService(); ReflectionTestUtils.setField(chat,"defaultArmoryFactory",factory);
            String[] inputs={"请切换到食物与烹饪课程。教材编号 bandready-daily，课程编号 food-cooking。", "切换为自由对话。", "以后重点练面试，请记住这个长期学习目标。"};
            for(int i=0;i<inputs.length;i++) {
                var invocation=new AgentInvocationVO("user:7","live-business",i+1,inputs[i],practice.state("live-business").snapshot().version(),false,false,()->true);
                String reply=chat.handlePreparedTextStream(table.getAgent().getAgentId(),"live-business",inputs[i],
                        "针对用户明确的操作调用业务工具，仅在工具成功后确认操作，用简短中文回复。",invocation)
                        .collectList().map(parts->String.join("",parts)).block(Duration.ofSeconds(90));
                assertNotNull(reply); assertFalse(reply.contains("\"ok\"")); assertFalse(reply.contains("SERVER_TOOL_CONTEXT"));
                if(i==0) assertEquals("food-cooking",practice.state("live-business").snapshot().lessonId());
                if(i==1) assertEquals("FREE",practice.state("live-business").snapshot().mode());
            }
            verify(profiles,atLeastOnce()).factFor(eq(7L),eq("learning_goal"),eq("GOAL"),anyString());
            assertEquals(2,practice.state("live-business").snapshot().version());
        }
    }
}

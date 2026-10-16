package cn.adinall.ai.trigger;

import cn.adinall.ai.cases.overview.HomeQueryCase;
import cn.adinall.ai.domain.coaching.conversation.adapter.port.IAvatarDriverPort;
import cn.adinall.ai.domain.coaching.conversation.model.valobj.*;
import cn.adinall.ai.domain.content.adapter.repository.IScenarioRepository;
import cn.adinall.ai.domain.content.model.valobj.ScenarioVO;
import cn.adinall.ai.domain.identity.adapter.repository.IProfileRepository;
import cn.adinall.ai.domain.identity.model.valobj.*;
import cn.adinall.ai.domain.identity.service.*;
import cn.adinall.ai.domain.learning.adapter.repository.ILearnerProgressRepository;
import cn.adinall.ai.domain.learning.model.valobj.EnglishLevelEnum;
import cn.adinall.ai.domain.learning.service.level.LearnerProgressService;
import cn.adinall.ai.trigger.http.*;
import org.junit.*;
import org.springframework.context.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.*;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import java.util.List;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * <b>功能</b>：验证移除空转发 Case 后的目录、首页和档位设置契约。
 * <p><b>体系架构 / 运行过程</b></p>
 * <pre>
 * MockMvc -> real account interceptor -> controller -> domain contract / home case
 * PUT level -> real transactional service proxy -> repository -> commit / rollback
 * </pre>
 * <p><b>案例</b>：账号 7 请求 {level:L3,userId:8} -> 只保存 user:7；保存后回读失败 -> 领域事务回滚。</p>
 * <b>设计要点</b>
 * <ul><li>检查 Android 实际使用的 JSON 字段和认证主体。</li>
 * <li>事务通过真实 Spring 代理执行，不以注解存在代替运行验证。</li></ul>
 */
public class ReadEndpointHttpTest {
    @Configuration @EnableWebMvc @EnableTransactionManagement
    @Import({AuthenticatedActorAdapter.class,AccountWebConfiguration.class, GlobalExceptionHandler.class, CatalogController.class,
            HomeController.class, HomeQueryCase.class, LearnerLevelController.class})
    public static class Web {
        @Bean public IAccountService accounts() { return mock(IAccountService.class); }
        @Bean public IAvatarDriverPort avatars() { return mock(IAvatarDriverPort.class); }
        @Bean public IScenarioRepository scenarios() { return mock(IScenarioRepository.class); }
        @Bean public IProfileRepository profiles() { return mock(IProfileRepository.class); }
        @Bean public IHomeService home() { return mock(IHomeService.class); }
        @Bean public ILearnerProgressRepository levels() { return mock(ILearnerProgressRepository.class); }
        @Bean public LearnerProgressService progress(ILearnerProgressRepository levels) { return new LearnerProgressService(levels); }
        @Bean public CountingTransactions transactionManager() { return new CountingTransactions(); }
    }

    public static class CountingTransactions extends AbstractPlatformTransactionManager {
        int commits;
        int rollbacks;
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object tx, TransactionDefinition definition) { }
        @Override protected void doCommit(DefaultTransactionStatus status) { commits++; }
        @Override protected void doRollback(DefaultTransactionStatus status) { rollbacks++; }
    }

    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;
    private IAvatarDriverPort avatars;
    private IProfileRepository profiles;
    private ILearnerProgressRepository levels;
    private CountingTransactions transactions;

    @Before public void setup() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext()); context.register(Web.class); context.refresh();
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        avatars = context.getBean(IAvatarDriverPort.class);
        profiles = context.getBean(IProfileRepository.class);
        levels = context.getBean(ILearnerProgressRepository.class);
        transactions = context.getBean(CountingTransactions.class);
        when(context.getBean(IAccountService.class).authenticate("t7")).thenReturn(new AccountPrincipalVO(7,"u7","account7"));
        when(avatars.listAvatars()).thenReturn(List.of(new AvatarProfileVO("anchor","伙伴","/cover","/preview",true)));
        when(profiles.get(7)).thenReturn(profile("anchor","voice-1"));
        when(context.getBean(IHomeService.class).voices()).thenReturn(List.of(new VoiceVO("voice-1","声音","tts")));
    }
    @After public void close() { context.close(); }

    @Test public void catalogRetainsAllClientFields() throws Exception {
        when(avatars.listModels()).thenReturn(List.of(new DriverModelVO("flashhead","模型",true,null,true,240L)));
        when(avatars.capabilities()).thenReturn(new DriverCapabilityVO("mock",true,true,true,true,true,List.of("flashhead")));
        when(context.getBean(IScenarioRepository.class).listAll()).thenReturn(List.of(new ScenarioVO("free","自由对话","聊天",null,"L2","Hello","voice-1")));
        mvc.perform(get("/api/v1/catalog/avatars").header("Authorization","Bearer t7"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].coverUrl").value("/cover"))
                .andExpect(jsonPath("$[0].previewVideoUrl").value("/preview")).andExpect(jsonPath("$[0].online").value(true));
        mvc.perform(get("/api/v1/catalog/models").header("Authorization","Bearer t7"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].isDefault").value(true)).andExpect(jsonPath("$[0].estimatedChunkMs").value(240));
        mvc.perform(get("/api/v1/catalog/capabilities").header("Authorization","Bearer t7"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.scriptedSay").value(true)).andExpect(jsonPath("$.supportedModels[0]").value("flashhead"));
        mvc.perform(get("/api/v1/catalog/scenarios").header("Authorization","Bearer t7"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].openingLine").value("Hello")).andExpect(jsonPath("$[0].voice").value("voice-1"));
        verifyNoInteractions(profiles, levels);
    }

    @Test public void homeCombinesTrustedSelectionAndMediaCatalog() throws Exception {
        mvc.perform(get("/api/v1/home?userId=8").header("Authorization","Bearer t7"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.avatars[0].id").value("anchor"))
                .andExpect(jsonPath("$.voices[0].id").value("voice-1"))
                .andExpect(jsonPath("$.selection.avatarId").value("anchor")).andExpect(jsonPath("$.selection.voice").value("voice-1"));
        verify(profiles).get(7); verify(profiles,never()).get(8); verifyNoInteractions(levels);
    }

    @Test public void homeDoesNotPretendFirstAvatarWasSaved() throws Exception {
        when(profiles.get(7)).thenReturn(profile(null,null));
        mvc.perform(get("/api/v1/home").header("Authorization","Bearer t7"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.selection.avatarId").isEmpty())
                .andExpect(jsonPath("$.selection.voice").isEmpty());
    }

    @Test public void manualLevelWritesOnlyCurrentAccountInsideDomainTransaction() throws Exception {
        when(levels.level("user:7")).thenReturn(EnglishLevelEnum.L2);
        when(levels.manualLevel("user:7")).thenReturn(EnglishLevelEnum.L3);
        doAnswer(call -> { assertTrue(TransactionSynchronizationManager.isActualTransactionActive()); return null; })
                .when(levels).setManualLevel("user:7",EnglishLevelEnum.L3);
        mvc.perform(put("/api/v1/me/english-level").header("Authorization","Bearer t7").contentType(MediaType.APPLICATION_JSON)
                .content("{\"level\":\"L3\",\"userId\":8}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.manualLevel").value("L3"))
                .andExpect(jsonPath("$.automaticLevel").value("L2"));
        verify(levels).setManualLevel("user:7",EnglishLevelEnum.L3);
        assertEquals(1,transactions.commits); assertEquals(0,transactions.rollbacks);
    }

    @Test public void invalidOrMissingIdentityNeverWritesLevels() throws Exception {
        mvc.perform(put("/api/v1/me/english-level").contentType(MediaType.APPLICATION_JSON).content("{\"level\":\"L3\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(put("/api/v1/me/english-level").header("Authorization","Bearer t7")
                .contentType(MediaType.APPLICATION_JSON).content("{\"level\":\"L7\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(levels);
    }

    @Test public void selectionReadbackFailureRollsBackAtDomainBoundary() throws Exception {
        when(levels.level("user:7")).thenThrow(new IllegalStateException("readback unavailable"));
        mvc.perform(put("/api/v1/me/english-level").header("Authorization","Bearer t7")
                .contentType(MediaType.APPLICATION_JSON).content("{\"level\":\"L3\"}"))
                .andExpect(status().isInternalServerError());
        verify(levels).setManualLevel("user:7",EnglishLevelEnum.L3);
        assertEquals(0,transactions.commits); assertEquals(1,transactions.rollbacks);
    }

    private AccountProfileVO profile(String avatar, String voice) {
        return new AccountProfileVO("u7","account7","昵称",null,avatar,voice,true,"on_demand");
    }
}

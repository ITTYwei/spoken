package cn.adinall.ai.trigger;

import cn.adinall.ai.trigger.http.*;
import cn.adinall.ai.domain.identity.service.IAccountService;
import cn.adinall.ai.domain.identity.model.valobj.AccountPrincipalVO;
import cn.adinall.ai.domain.coaching.history.adapter.repository.IConversationHistoryRepository;
import cn.adinall.ai.domain.coaching.history.service.ConversationHistoryService;
import cn.adinall.ai.domain.coaching.history.model.valobj.SessionHistoryVO;
import org.junit.*;
import org.springframework.context.annotation.*;
import org.springframework.mock.web.MockServletContext;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * <b>功能</b>：验证持久历史接口的认证、归属、分页和关闭后读取行为。
 * <p><b>体系架构 / 运行过程</b></p>
 * <pre>MockMvc -> real auth interceptor -> history controller -> domain service -> repository mock</pre>
 * <p><b>案例</b>：账号 7 携带 userId=8 查询 -> 仍只向仓储查询账号 7 的会话。</p>
 * <b>设计要点</b>
 * <ul><li>未认证请求在读取档案前被拒绝。</li><li>服务层仍验证归属，不能只依赖路径拦截器。</li></ul>
 */
public class ConversationHistoryHttpTest {
    @Configuration @EnableWebMvc
    @Import({AccountWebConfiguration.class, ConversationHistoryController.class, GlobalExceptionHandler.class})
    public static class Web {
        @Bean public IAccountService accounts() { return mock(IAccountService.class); }
        @Bean public IConversationHistoryRepository repository() { return mock(IConversationHistoryRepository.class); }
        @Bean public ConversationHistoryService history(IConversationHistoryRepository repository) {
            return new ConversationHistoryService(repository);
        }
    }
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;
    private IConversationHistoryRepository repository;
    @Before public void setup() {
        context=new AnnotationConfigWebApplicationContext();context.setServletContext(new MockServletContext());
        context.register(Web.class);context.refresh();mvc=MockMvcBuilders.webAppContextSetup(context).build();
        repository=context.getBean(IConversationHistoryRepository.class);
        when(context.getBean(IAccountService.class).authenticate("t7")).thenReturn(new AccountPrincipalVO(7L,"u7","account7"));
    }
    @After public void close() { context.close(); }
    @Test public void unauthenticatedRequestReadsNothing() throws Exception {
        mvc.perform(get("/api/v1/chat-history/sessions")).andExpect(status().isUnauthorized());
        verifyNoInteractions(repository);
    }
    @Test public void clientCannotSelectAnotherAccount() throws Exception {
        when(repository.sessions(7,null,21)).thenReturn(List.of());
        mvc.perform(get("/api/v1/chat-history/sessions?userId=8").header("Authorization","Bearer t7"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
        verify(repository).sessions(7,null,21);
    }
    @Test public void closedSessionIsReadableAndOtherAccountIsNot() throws Exception {
        when(repository.session(7,"s-1")).thenReturn(Optional.of(new SessionHistoryVO("s-1","a","v","free","CLOSED",1000,2000L)));
        when(repository.turns(7,"s-1",0,21)).thenReturn(List.of());
        mvc.perform(get("/api/v1/chat-history/sessions/s-1/turns").header("Authorization","Bearer t7"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/chat-history/sessions/other/turns").header("Authorization","Bearer t7"))
                .andExpect(status().isNotFound());
        verify(repository,never()).turns(eq(7L),eq("other"),anyLong(),anyInt());
    }
    @Test public void malformedCursorIsRejected() throws Exception {
        mvc.perform(get("/api/v1/chat-history/sessions?beforeStartedAt=1000").header("Authorization","Bearer t7"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(repository);
    }
}

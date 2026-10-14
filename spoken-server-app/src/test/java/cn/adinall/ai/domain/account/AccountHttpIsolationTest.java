package cn.adinall.ai.domain.account;

import cn.adinall.ai.domain.account.support.AccountContext;
import cn.adinall.ai.domain.account.service.impl.AccountService;
import cn.adinall.ai.domain.account.service.impl.HomeService;
import cn.adinall.ai.domain.account.model.valobj.AccountPrincipalVO;

import cn.adinall.ai.cases.conversation.IConversationCase;
import cn.adinall.ai.trigger.http.*;
import cn.adinall.ai.types.exception.AppException;
import org.junit.*;
import org.springframework.context.annotation.*;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.Assert.*;

/** 验证HTTP真实拦截链：未登录及越权在调用业务前拒绝，并清理线程身份。 */
public class AccountHttpIsolationTest {
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;
    private AccountService accounts;
    private IConversationCase conversations;
    @Configuration @EnableWebMvc
    @Import({AccountWebConfiguration.class, SessionController.class, AccountController.class, GlobalExceptionHandler.class})
    public static class Web {
        @Bean public AccountService accounts() { return mock(AccountService.class); }
        @Bean public HomeService home() { return mock(HomeService.class); }
        @Bean public IConversationCase conversations() { return mock(IConversationCase.class); }
    }
    @Before public void setup() {
        context = new AnnotationConfigWebApplicationContext(); context.setServletContext(new MockServletContext());
        context.register(Web.class); context.refresh();
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        accounts = context.getBean(AccountService.class); conversations = context.getBean(IConversationCase.class);
        when(accounts.authenticate("valid-token")).thenReturn(new AccountPrincipalVO(1, "user-one", "learner_one"));
    }
    @After public void close() { AccountContext.clear(); context.close(); }
    @Test public void privateRoutesRequireLogin() throws Exception {
        mvc.perform(get("/api/v1/sessions/any-session")).andExpect(status().isUnauthorized());
        verifyNoInteractions(conversations); assertNull(AccountContext.current());
    }
    @Test public void allSessionSubresourcesShareOwnershipGate() throws Exception {
        doThrow(new AppException("session_not_found", "会话不存在")).when(accounts).requireSessionOwner("other-session");
        mvc.perform(get("/api/v1/sessions/other-session/events").header("Authorization", "Bearer valid-token")).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/sessions/other-session/interrupt").header("Authorization", "Bearer valid-token")).andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/sessions/other-session").header("Authorization", "Bearer valid-token")).andExpect(status().isNotFound());
        verifyNoInteractions(conversations); assertNull(AccountContext.current());
    }
    @Test public void successfulRequestDoesNotLeaveIdentityOnServletThread() throws Exception {
        mvc.perform(get("/api/v1/sessions/own-session").header("Authorization", "Bearer valid-token")).andExpect(status().isOk());
        verify(accounts).requireSessionOwner("own-session"); verify(conversations).get("own-session");
        assertNull(AccountContext.current());
    }
    @Test public void logoutAcceptsPreviouslyRevokedTokenWithoutAuthentication() throws Exception {
        mvc.perform(post("/api/v1/auth/logout").header("Authorization", "Bearer revoked-token")).andExpect(status().isOk());
        verify(accounts).logout("revoked-token"); verify(accounts, never()).authenticate(anyString());
        mvc.perform(post("/api/v1/auth/logout")).andExpect(status().isBadRequest());
    }
}

package cn.adinall.ai.domain.companion;

import com.google.adk.sessions.BaseSessionService;
import java.util.concurrent.CountDownLatch;
import reactor.core.Disposable;

import cn.adinall.ai.infrastructure.gateway.agent.chat.ChatService;
import cn.adinall.ai.infrastructure.gateway.agent.armory.factory.DefaultArmoryFactory;
import cn.adinall.ai.infrastructure.gateway.agent.model.AiAgentRegisterVO;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.sessions.*;
import com.google.adk.events.Event;
import com.google.genai.types.*;
import io.reactivex.rxjava3.core.*;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/**
 * 功能：验证不同Agent缓存隔离、教学短会话释放以及取消清理。
 * <p>体系架构 / 运行过程：</p>
 * <pre>mock ADK sessions -> real ChatService -> stream completion/cancel -> delete</pre>
 * <p>案例：同用户换Agent -> 两个session；教学结束 -> 删除短session。</p>
 * <p>设计要点：</p>
 * <ul><li>不请求LLM。</li><li>验证资源释放而非只测返回文字。</li></ul>
 */
public class AgentSessionIsolationTest {
    private ChatService service(DefaultArmoryFactory factory) {
        ChatService service=new ChatService(); ReflectionTestUtils.setField(service,"defaultArmoryFactory",factory); return service;
    }
    private AiAgentRegisterVO register(InMemoryRunner runner) { return AiAgentRegisterVO.builder().appName("app").runner(runner).build(); }
    @Test public void sameUserDifferentAgentDoesNotReuseSession() {
        DefaultArmoryFactory factory=mock(DefaultArmoryFactory.class); InMemoryRunner a=mock(InMemoryRunner.class); InMemoryRunner b=mock(InMemoryRunner.class);
        BaseSessionService as=mock(BaseSessionService.class); BaseSessionService bs=mock(BaseSessionService.class);
        when(a.sessionService()).thenReturn(as); when(b.sessionService()).thenReturn(bs);
        when(as.createSession("app","u")).thenReturn(Single.just(Session.builder("app").id("a").build()));
        when(bs.createSession("app","u")).thenReturn(Single.just(Session.builder("app").id("b").build()));
        when(factory.getAiAgentRegisterVO("a")).thenReturn(register(a)); when(factory.getAiAgentRegisterVO("b")).thenReturn(register(b));
        ChatService service=service(factory); assertEquals("a",service.createSession("a","u")); assertEquals("b",service.createSession("b","u"));
        assertEquals("a",service.createSession("a","u")); verify(as).createSession("app","u");
    }
    @Test public void preparedStreamReleasesSessionAfterCompletion() {
        DefaultArmoryFactory factory=mock(DefaultArmoryFactory.class); InMemoryRunner runner=mock(InMemoryRunner.class); BaseSessionService sessions=mock(BaseSessionService.class);
        when(factory.getAiAgentRegisterVO("a")).thenReturn(register(runner)); when(runner.sessionService()).thenReturn(sessions);
        when(sessions.createSession(eq("app"),eq("u"),any(),isNull())).thenReturn(Single.just(Session.builder("app").id("short").build()));
        when(sessions.deleteSession("app","u","short")).thenReturn(Completable.complete());
        when(runner.runAsync(eq("u"),eq("short"),any(Content.class),any())).thenReturn(Flowable.empty());
        service(factory).handlePreparedTextStream("a","u","CURRENT L2").collectList().block();
        verify(sessions).deleteSession("app","u","short");
    }
    @Test public void cancellationDisposesModelAndDeletesShortSession() throws Exception {
        DefaultArmoryFactory factory=mock(DefaultArmoryFactory.class); InMemoryRunner runner=mock(InMemoryRunner.class); BaseSessionService sessions=mock(BaseSessionService.class);
        when(factory.getAiAgentRegisterVO("a")).thenReturn(register(runner)); when(runner.sessionService()).thenReturn(sessions);
        when(sessions.createSession(eq("app"),eq("u"),any(),isNull())).thenReturn(Single.just(Session.builder("app").id("short").build()));
        when(sessions.deleteSession("app","u","short")).thenReturn(Completable.complete());
        CountDownLatch started=new java.util.concurrent.CountDownLatch(1); CountDownLatch cancelled=new java.util.concurrent.CountDownLatch(1);
        when(runner.runAsync(eq("u"),eq("short"),any(Content.class),any())).thenReturn(Flowable.<Event>never().doOnSubscribe(s -> started.countDown()).doOnCancel(cancelled::countDown));
        Disposable subscription=service(factory).handlePreparedTextStream("a","u","CURRENT").subscribe();
        assertTrue(started.await(3,java.util.concurrent.TimeUnit.SECONDS)); subscription.dispose();
        assertTrue(cancelled.await(3,java.util.concurrent.TimeUnit.SECONDS)); verify(sessions,timeout(3000)).deleteSession("app","u","short");
    }
}

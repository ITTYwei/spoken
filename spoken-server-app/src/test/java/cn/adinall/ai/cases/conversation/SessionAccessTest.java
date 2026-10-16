package cn.adinall.ai.cases.conversation;

import cn.adinall.ai.cases.security.SessionAccess;

import cn.adinall.ai.cases.security.*;
import cn.adinall.ai.domain.identity.adapter.repository.IAccountRepository;
import cn.adinall.ai.domain.identity.model.valobj.AccountPrincipalVO;
import cn.adinall.ai.types.exception.AppException;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class SessionAccessTest {
    @Test public void applicationAuthorizationWorksWithCapturedActorWithoutRequestThreadLocal() {
        IAccountRepository accounts=mock(IAccountRepository.class);
        when(accounts.ownsSession(7,"own")).thenReturn(true);
        SessionAccess access=new SessionAccess(() -> new AccountPrincipalVO(7,"u7","test"),accounts);
        access.requireOwner("own");
        try { access.requireOwner("other"); fail(); }
        catch(AppException expected) { assertEquals("session_not_found",expected.code()); }
        verify(accounts).ownsSession(7,"other");
    }
}

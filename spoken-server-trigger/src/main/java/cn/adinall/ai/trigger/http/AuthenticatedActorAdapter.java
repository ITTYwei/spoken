package cn.adinall.ai.trigger.http;

import cn.adinall.ai.cases.security.ICurrentActor;
import cn.adinall.ai.domain.identity.model.valobj.AccountPrincipalVO;
import cn.adinall.ai.domain.identity.support.AccountContext;
import org.springframework.stereotype.Component;

@Component
public class AuthenticatedActorAdapter implements ICurrentActor {
    /** 取得已认证主体；未登录时拒绝调用。 */
    @Override public AccountPrincipalVO require() { return AccountContext.require(); }
}

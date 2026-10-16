package cn.adinall.ai.cases.security;

import cn.adinall.ai.domain.identity.adapter.repository.IAccountRepository;
import cn.adinall.ai.types.exception.AppException;
import org.springframework.stereotype.Component;

/** 统一应用层权限检查，HTTP 与其他已认证入口采用相同的会话归属规则。 */
@Component
public class SessionAccess {
    private final ICurrentActor actors;
    private final IAccountRepository accounts;
    /** 创建 {@code SessionAccess}，初始化其依赖与状态。 */
    public SessionAccess(ICurrentActor actors, IAccountRepository accounts) { this.actors = actors; this.accounts = accounts; }
    /** 校验当前登录账号是否拥有指定会话，不属于该账号时拒绝访问。 */
    public void requireOwner(String sessionId) {
        if (!accounts.ownsSession(actors.require().id(), sessionId))
            throw new AppException("session_not_found", "会话不存在或已结束");
    }
}

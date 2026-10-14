package cn.adinall.ai.domain.account.adapter.repository;

import cn.adinall.ai.domain.account.model.valobj.AccountCredentialVO;

import java.time.Instant;
import cn.adinall.ai.domain.account.model.valobj.AccountPrincipalVO;

/** 账号存储端口；令牌和密码变更在仓储事务中串行化，防止撤销后出现迟到登录。 */
public interface IAccountRepository {
    AccountPrincipalVO register(String account, String hash, String userNo);
    AccountCredentialVO credential(String account);
    boolean issue(AccountCredentialVO expected, String tokenHash, Instant expires);
    AccountPrincipalVO authenticate(String tokenHash, Instant now);
    void revoke(String tokenHash);
    boolean changePassword(long userId, String expectedHash, String newHash);
    boolean ownsSession(long userId, String sessionId);
}

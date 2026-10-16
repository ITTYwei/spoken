package cn.adinall.ai.domain.identity.adapter.repository;

import cn.adinall.ai.domain.identity.model.valobj.AccountCredentialVO;

import java.time.Instant;
import cn.adinall.ai.domain.identity.model.valobj.AccountPrincipalVO;

/** 账号存储端口；令牌和密码变更在仓储事务中串行化，防止撤销后出现迟到登录。 */
public interface IAccountRepository {
    /** 注册账号并返回认证主体信息。 */
    AccountPrincipalVO register(String account, String hash, String userNo);
    /** 根据登录账号读取密码校验所需的凭据。 */
    AccountCredentialVO credential(String account);
    /** 在凭据仍有效时签发登录令牌，避免与密码变更发生竞争。 */
    boolean issue(AccountCredentialVO expected, String tokenHash, Instant expires);
    /** 根据令牌摘要与当前时间校验登录状态。 */
    AccountPrincipalVO authenticate(String tokenHash, Instant now);
    /** 撤销指定令牌的登录状态。 */
    void revoke(String tokenHash);
    /** 更新账号密码摘要。 */
    boolean changePassword(long userId, String expectedHash, String newHash);
    /** 判断指定账号是否拥有目标会话。 */
    boolean ownsSession(long userId, String sessionId);
}

package cn.adinall.ai.infrastructure.dao;

import cn.adinall.ai.infrastructure.dao.po.AccountCredentialPO;
import org.apache.ibatis.annotations.*;
import java.time.Instant;

/** 账号、凭证及令牌SQL契约；多表事务由账户仓储统一控制。 */
@Mapper
public interface IAccountDao {
    /** 回填自增用户ID，随后在同一事务创建凭证与资料行。 */
    int insertUser(AccountCredentialPO account);
    int insertIdentity(AccountCredentialPO account);
    int insertProfile(@Param("userId") long userId);
    AccountCredentialPO credential(@Param("account") String account);
    /** 发令牌与改密共用此行锁，必须在仓储事务中调用。 */
    String lockCredential(@Param("userId") long userId);
    int insertToken(@Param("userId") long userId, @Param("tokenHash") String tokenHash, @Param("expires") Instant expires);
    int lastLogin(@Param("userId") long userId, @Param("at") long at);
    AccountCredentialPO authenticate(@Param("tokenHash") String tokenHash, @Param("now") Instant now);
    int revoke(@Param("tokenHash") String tokenHash);
    int changePassword(@Param("userId") long userId, @Param("hash") String hash);
    int revokeAll(@Param("userId") long userId);
    boolean ownsSession(@Param("userId") long userId, @Param("sessionId") String sessionId);
}

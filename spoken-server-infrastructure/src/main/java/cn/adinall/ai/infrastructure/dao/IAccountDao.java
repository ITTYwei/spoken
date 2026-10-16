package cn.adinall.ai.infrastructure.dao;

import cn.adinall.ai.infrastructure.dao.po.AccountCredentialPO;
import org.apache.ibatis.annotations.*;
import java.time.Instant;

/** 账号、凭证及令牌SQL契约；多表事务由账户仓储统一控制。 */
@Mapper
public interface IAccountDao {
    /** 回填自增用户ID，随后在同一事务创建凭证与资料行。 */
    int insertUser(AccountCredentialPO account);
    /** 插入账号身份与密码凭据记录。 */
    int insertIdentity(AccountCredentialPO account);
    /** 为新账号创建默认个人资料记录。 */
    int insertProfile(@Param("userId") long userId);
    /** 根据登录账号读取密码校验所需的凭据。 */
    AccountCredentialPO credential(@Param("account") String account);
    /** 发令牌与改密共用此行锁，必须在仓储事务中调用。 */
    String lockCredential(@Param("userId") long userId);
    /** 保存令牌摘要及过期时间。 */
    int insertToken(@Param("userId") long userId, @Param("tokenHash") String tokenHash, @Param("expires") Instant expires);
    /** 更新账号最近登录时间。 */
    int lastLogin(@Param("userId") long userId, @Param("at") long at);
    /** 根据令牌摘要与当前时间校验登录状态。 */
    AccountCredentialPO authenticate(@Param("tokenHash") String tokenHash, @Param("now") Instant now);
    /** 撤销指定令牌的登录状态。 */
    int revoke(@Param("tokenHash") String tokenHash);
    /** 更新账号密码摘要。 */
    int changePassword(@Param("userId") long userId, @Param("hash") String hash);
    /** 撤销账号当前全部登录令牌。 */
    int revokeAll(@Param("userId") long userId);
    /** 判断指定账号是否拥有目标会话。 */
    boolean ownsSession(@Param("userId") long userId, @Param("sessionId") String sessionId);
}

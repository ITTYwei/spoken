package cn.adinall.ai.infrastructure.adapter.repository;

import cn.adinall.ai.domain.account.adapter.repository.IAccountRepository;
import cn.adinall.ai.domain.account.model.valobj.*;
import cn.adinall.ai.infrastructure.dao.IAccountDao;
import cn.adinall.ai.infrastructure.dao.po.AccountCredentialPO;
import cn.adinall.ai.types.exception.AppException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;

/** 账户仓储：转换PO与领域对象，组织多表事务；SQL由DAO和Mapper承担。 */
@Repository
public class AccountRepository implements IAccountRepository {
    private final IAccountDao dao;
    public AccountRepository(IAccountDao dao) { this.dao = dao; }

    /** 注册的三张表必须同时成功；并发账号冲突时回滚已插入的用户行。 */
    @Override @Transactional
    public AccountPrincipalVO register(String account, String hash, String userNo) {
        var row = new AccountCredentialPO();
        row.setAccount(account); row.setSecretHash(hash); row.setUserNo(userNo);
        try {
            dao.insertUser(row);
            // 自增ID由Mapper回填，后续写入共用同一个事务和用户归属。
            dao.insertIdentity(row);
            dao.insertProfile(row.getUserId());
            return principal(row);
        } catch (DuplicateKeyException e) { throw new AppException("account_exists", "账号已被注册"); }
    }
    @Override public AccountCredentialVO credential(String account) {
        var row = dao.credential(account);
        return row == null ? null : new AccountCredentialVO(principal(row), row.getSecretHash());
    }
    /** 发令牌与改密共用凭证行锁，阻止旧密码的迟到登录重新建立有效令牌。 */
    @Override @Transactional
    public boolean issue(AccountCredentialVO expected, String tokenHash, Instant expires) {
        long id = expected.principal().id();
        String current = dao.lockCredential(id);
        if (current == null || !current.equals(expected.hash())) return false;
        dao.insertToken(id, tokenHash, expires);
        dao.lastLogin(id, System.currentTimeMillis());
        return true;
    }
    @Override public AccountPrincipalVO authenticate(String hash, Instant now) {
        var row = dao.authenticate(hash, now);
        return row == null ? null : principal(row);
    }
    @Override public void revoke(String hash) { dao.revoke(hash); }
    /** 密码更新和所有设备令牌撤销是一个原子操作。 */
    @Override @Transactional
    public boolean changePassword(long id, String expectedHash, String newHash) {
        String current = dao.lockCredential(id);
        if (current == null || !current.equals(expectedHash)) return false;
        dao.changePassword(id, newHash);
        // 登录先拿到锁时，其新令牌也会在这里被撤销。
        dao.revokeAll(id);
        return true;
    }
    @Override public boolean ownsSession(long id, String session) { return dao.ownsSession(id, session); }
    /** 数据库投影仅在基础设施层使用，不向领域暴露列名或PO。 */
    private AccountPrincipalVO principal(AccountCredentialPO row) {
        return new AccountPrincipalVO(row.getUserId(), row.getUserNo(), row.getAccount());
    }
}

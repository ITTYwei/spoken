package cn.adinall.ai.domain.account;

import cn.adinall.ai.domain.account.service.security.TokenHasher;

import cn.adinall.ai.domain.account.support.AccountContext;
import cn.adinall.ai.domain.account.service.security.PasswordHasher;
import cn.adinall.ai.domain.account.adapter.repository.IAccountRepository;
import cn.adinall.ai.domain.account.service.impl.AccountService;
import cn.adinall.ai.domain.account.service.impl.ProfileService;
import cn.adinall.ai.domain.account.model.valobj.AccountCredentialVO;

import cn.adinall.ai.domain.account.model.valobj.AccountPrincipalVO;
import cn.adinall.ai.domain.learner.model.valobj.*;
import cn.adinall.ai.types.exception.AppException;
import org.junit.*;
import java.time.Instant;
import java.util.*;
import static org.junit.Assert.*;

/** 验证账户撤销与画像证据门槛；不连接外部模型或数字人服务。 */
public class AccountPhaseOneTest {
    @After public void clearIdentity() { AccountContext.clear(); }
    @Test public void passwordsUseDifferentSaltsAndRejectWrongInput() {
        String one = PasswordHasher.hash("password123");
        String two = PasswordHasher.hash("password123");
        assertNotEquals(one, two);
        assertTrue(PasswordHasher.matches("password123", one));
        assertFalse(PasswordHasher.matches("wrongpassword", one));
        assertFalse(PasswordHasher.matches("password123", "broken"));
    }
    @Test public void logoutAndPasswordChangeInvalidateLogin() {
        var store = new FakeAccounts();
        var service = new AccountService(store);
        var first = service.register("Learner_1", "password123");
        var principal = service.authenticate(first.token());
        assertEquals("learner_1", principal.account());
        assertEquals(1, store.credentials.size());
        service.logout(first.token());
        expectUnauthorized(() -> service.authenticate(first.token()));
        var deviceOne = service.login("learner_1", "password123");
        var deviceTwo = service.login("learner_1", "password123");
        AccountContext.bind(principal);
        service.changePassword("password123", "newpassword123");
        expectUnauthorized(() -> service.authenticate(deviceOne.token()));
        expectUnauthorized(() -> service.authenticate(deviceTwo.token()));
        expectUnauthorized(() -> service.login("learner_1", "password123"));
        assertNotNull(service.authenticate(service.login("learner_1", "newpassword123").token()));
    }
    @Test public void crossAccountSessionIsHiddenAndContextIsCleared() {
        var store = new FakeAccounts();
        var service = new AccountService(store);
        var first = service.register("account_a", "password123");
        var second = service.register("account_b", "password123");
        var owner = service.authenticate(first.token());
        store.sessions.put("session-a", owner.id());
        AccountContext.bind(service.authenticate(second.token()));
        try { service.requireSessionOwner("session-a"); fail(); }
        catch (AppException e) { assertEquals("session_not_found", e.code()); }
        AccountContext.bind(owner);
        service.requireSessionOwner("session-a");
        AccountContext.clear();
        expectUnauthorized(AccountContext::require);
    }
    @Test public void expiredTokenCannotKeepLoggedIn() {
        var store = new FakeAccounts();
        var service = new AccountService(store);
        var login = service.register("learner_2", "password123");
        store.expires.put(TokenHasher.digest(login.token()), Instant.now().minusSeconds(1));
        expectUnauthorized(() -> service.authenticate(login.token()));
    }
    @Test public void abilityRequiresRecentIndependentCrossSessionEvidence() {
        assertEquals("正在了解", ProfileService.ability(evidence(19, 3, true)).description());
        assertEquals("learning", ProfileService.ability(evidence(40, 1, true)).status());
        assertEquals("learning", ProfileService.ability(evidence(40, 3, false)).status());
        var duplicates = new ArrayList<>(evidence(10, 3, true));
        duplicates.addAll(duplicates.subList(0, 10));
        assertEquals("learning", ProfileService.ability(duplicates).status());
        var ready = ProfileService.ability(evidence(21, 3, true));
        assertEquals("ready", ready.status());
        assertFalse(ready.description().contains("L4"));
        assertFalse(ready.description().contains("分"));
    }
    private List<LearningEvidenceVO> evidence(int count, int sessions, boolean eligible) {
        List<LearningEvidenceVO> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) rows.add(new LearningEvidenceVO("user:1", "s" + i % sessions, (long)i,
                "ANSWERED", EnglishLevelEnum.L4, .95, eligible, Instant.now().minusSeconds(i)));
        return rows;
    }
    private void expectUnauthorized(Runnable action) {
        try { action.run(); fail("应拒绝无效登录态"); }
        catch (AppException e) { assertEquals("unauthorized", e.code()); }
    }
    /** 内存替身验证领域行为；真实SQL和事务由独立数据库集成测试覆盖。 */
    private static class FakeAccounts implements IAccountRepository {
        final Map<String, AccountCredentialVO> credentials = new HashMap<>();
        final Map<String, AccountPrincipalVO> tokens = new HashMap<>();
        final Map<String, Instant> expires = new HashMap<>();
        final Map<String, Long> sessions = new HashMap<>();
        public AccountPrincipalVO register(String account, String hash, String userNo) {
            if (credentials.containsKey(account)) throw new AppException("account_exists", "重复账号");
            var principal = new AccountPrincipalVO(credentials.size() + 1, userNo, account);
            credentials.put(account, new AccountCredentialVO(principal, hash)); return principal;
        }
        public AccountCredentialVO credential(String account) { return credentials.get(account); }
        public boolean issue(AccountCredentialVO credential, String hash, Instant expiry) {
            if (!credential.hash().equals(credentials.get(credential.principal().account()).hash())) return false;
            tokens.put(hash, credential.principal()); expires.put(hash, expiry); return true;
        }
        public AccountPrincipalVO authenticate(String hash, Instant now) { return expires.containsKey(hash) && expires.get(hash).isAfter(now) ? tokens.get(hash) : null; }
        public void revoke(String hash) { tokens.remove(hash); }
        public boolean changePassword(long id, String expected, String replacement) {
            var existing = credentials.values().stream().filter(c -> c.principal().id() == id && c.hash().equals(expected)).findFirst();
            if (existing.isEmpty()) return false;
            var c = existing.get(); credentials.put(c.principal().account(), new AccountCredentialVO(c.principal(), replacement));
            tokens.entrySet().removeIf(e -> e.getValue().id() == id); return true;
        }
        public boolean ownsSession(long id, String session) { return Objects.equals(sessions.get(session), id); }
    }
}

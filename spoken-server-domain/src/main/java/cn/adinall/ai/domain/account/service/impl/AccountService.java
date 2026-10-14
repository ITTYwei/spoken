package cn.adinall.ai.domain.account.service.impl;

import cn.adinall.ai.domain.account.service.IAccountService;

import cn.adinall.ai.domain.account.support.AccountContext;
import cn.adinall.ai.domain.account.service.security.PasswordHasher;

import static cn.adinall.ai.domain.account.service.security.TokenHasher.digest;

import cn.adinall.ai.domain.account.adapter.repository.IAccountRepository;
import cn.adinall.ai.domain.account.model.valobj.AccountPrincipalVO;
import cn.adinall.ai.domain.account.model.valobj.LoginResultVO;

import cn.adinall.ai.types.exception.AppException;
import org.springframework.stereotype.Service;

import java.security.*;
import java.time.*;
import java.util.*;

/**
 * 账户闭环：注册仅需账号密码；登录令牌持久保存30天，退出和改密可立即撤销。
 */
@Service
public class AccountService implements IAccountService {
    private final IAccountRepository repository;
    // 不存在的账号也进行一次密码计算，减小凭响应耗时枚举账号的差异。
    private final String dummyHash = PasswordHasher.hash("unused-dummy-password");

    public AccountService(IAccountRepository repository) {
        this.repository = repository;
    }

    /**
     * 注册只创建账号，不收集学习水平、兴趣或目标。
     */
    public LoginResultVO register(String account, String password) {
        String normalized = normalize(account);
        validatePassword(password);
        var principal = repository.register(normalized, PasswordHasher.hash(password), UUID.randomUUID().toString().replace("-", ""));
        return login(principal.account(), password);
    }

    /**
     * 校验凭证并生成高熵令牌；客户端保存明文，服务器只保存摘要。
     */
    public LoginResultVO login(String account, String password) {
        String normalized = normalize(account);
        validatePassword(password);
        var credential = repository.credential(normalized);
        boolean valid = PasswordHasher.matches(password, credential == null ? dummyHash : credential.hash());
        if (!valid || credential == null) throw new AppException("unauthorized", "账号或密码不正确");
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant expires = Instant.now().plus(Duration.ofDays(30));
        // 发令牌时复核密码摘要，阻止与修改密码并发的旧密码登录。
        if (!repository.issue(credential, digest(token), expires))
            throw new AppException("unauthorized", "账号状态已变化，请重新登录");
        return new LoginResultVO(token, expires, credential.principal().userNo(), normalized);
    }

    /**
     * 每次请求校验有效期、撤销状态和账号状态，不依赖进程内缓存。
     */
    public AccountPrincipalVO authenticate(String token) {
        if (token == null || token.length() < 32 || token.length() > 128)
            throw new AppException("unauthorized", "登录已失效");
        var principal = repository.authenticate(digest(token), Instant.now());
        if (principal == null) throw new AppException("unauthorized", "登录已失效，请重新登录");
        return principal;
    }

    /**
     * 退出可重复调用；已撤销或已过期令牌不恢复成有效状态。
     */
    public void logout(String token) {
        repository.revoke(digest(token));
    }

    /**
     * 校验旧密码后撤销该账号全部登录态，客户端需要重新登录。
     */
    public void changePassword(String oldPassword, String newPassword) {
        validatePassword(newPassword);
        var principal = AccountContext.require();
        var credential = repository.credential(principal.account());
        if (credential == null || !PasswordHasher.matches(oldPassword, credential.hash()))
            throw new AppException("unauthorized", "原密码不正确");
        if (!repository.changePassword(principal.id(), credential.hash(), PasswordHasher.hash(newPassword)))
            throw new AppException("unauthorized", "密码已变化，请重新登录");
    }

    /**
     * 他人的会话和不存在的会话返回同一个404，避免暴露资源存在性。
     */
    public void requireSessionOwner(String sessionId) {
        if (!repository.ownsSession(AccountContext.require().id(), sessionId))
            throw new AppException("session_not_found", "会话不存在或已结束");
    }

    public static String normalize(String account) {
        if (account == null || !account.matches("[A-Za-z0-9_]{4,32}"))
            throw new IllegalArgumentException("账号需为4至32位字母、数字或下划线");
        return account.toLowerCase(Locale.ROOT);
    }

    private static void validatePassword(String password) {
        if (password == null || password.length() < 8 || password.length() > 128)
            throw new IllegalArgumentException("密码长度需为8至128位");
    }
}

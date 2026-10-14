package cn.adinall.ai.domain.account.support;

import cn.adinall.ai.domain.account.model.valobj.AccountPrincipalVO;

/**
 * 请求内的可信身份；由认证入口设置，异步教学只使用会话中已捕获的主体。
 */
public final class AccountContext {
    private static final ThreadLocal<AccountPrincipalVO> CURRENT = new ThreadLocal<>();

    private AccountContext() {
    }

    /**
     * 获取身份，禁止把客户端 userId 或 deviceId 当作认证依据。
     */
    public static AccountPrincipalVO require() {
        var principal = CURRENT.get();
        if (principal == null) throw new cn.adinall.ai.types.exception.AppException("unauthorized", "请先登录");
        return principal;
    }

    public static AccountPrincipalVO current() {
        return CURRENT.get();
    }

    public static void bind(AccountPrincipalVO principal) {
        CURRENT.set(principal);
    }

    /**
     * Servlet 线程会被复用，请求结束或转为异步后必须清理身份。
     */
    public static void clear() {
        CURRENT.remove();
    }
}

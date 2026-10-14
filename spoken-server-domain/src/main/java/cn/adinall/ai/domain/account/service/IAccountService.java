package cn.adinall.ai.domain.account.service;

import cn.adinall.ai.domain.account.model.valobj.*;

/**
 * 账户闭环服务契约；HTTP认证入口只依赖接口，不依赖实现类。
 */
public interface IAccountService {
    /**
     * 仅创建账号和密码凭证，不询问学习信息。
     */
    LoginResultVO register(String account, String password);

    /**
     * 校验账号密码并签发持久登录令牌。
     */
    LoginResultVO login(String account, String password);

    /**
     * 每次请求检查令牌有效期、撤销状态和账号状态。
     */
    AccountPrincipalVO authenticate(String token);

    /**
     * 幂等撤销当前令牌，已失效令牌不会重新变为有效。
     */
    void logout(String token);

    /**
     * 更新密码并撤销当前账号全部设备的登录态。
     */
    void changePassword(String oldPassword, String newPassword);

    /**
     * 不存在与非本人会话统一拒绝，不暴露资源存在性。
     */
    void requireSessionOwner(String sessionId);
}

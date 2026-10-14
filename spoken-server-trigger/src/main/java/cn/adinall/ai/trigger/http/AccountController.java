package cn.adinall.ai.trigger.http;

import cn.adinall.ai.domain.account.model.valobj.LoginResultVO;

import cn.adinall.ai.domain.account.service.IAccountService;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

/** 账号入口；注册DTO只含账号密码，响应中的Bearer令牌由客户端安全保存。 */
@RestController
@RequestMapping("/api/v1/auth")
public class AccountController {
    private final IAccountService service;
    public AccountController(IAccountService service) { this.service = service; }
    public record Credentials(String account, String password) { }
    public record PasswordChange(String oldPassword, String newPassword) { }
    /** 创建账号并直接登录，不引导填写学习问卷。 */
    @PostMapping("/register")
    public LoginResultVO register(@RequestBody Credentials request) {
        return service.register(request.account(), request.password());
    }
    @PostMapping("/login")
    public LoginResultVO login(@RequestBody Credentials request) {
        return service.login(request.account(), request.password());
    }
    /** 幂等撤销当前令牌；过期令牌也允许退出，方便客户端清理本地状态。 */
    @PostMapping("/logout")
    public Map<String, String> logout(@RequestHeader("Authorization") String authorization) {
        service.logout(AccountWebConfiguration.bearer(authorization));
        return Map.of("status", "logged_out");
    }
    @PostMapping("/password")
    public Map<String, String> password(@RequestBody PasswordChange request) {
        service.changePassword(request.oldPassword(), request.newPassword());
        return Map.of("status", "login_required");
    }
}

package cn.adinall.ai.trigger.http;

import cn.adinall.ai.domain.account.support.AccountContext;
import cn.adinall.ai.domain.account.service.IAccountService;

import cn.adinall.ai.types.exception.AppException;
import jakarta.servlet.http.*;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.*;
import org.springframework.web.servlet.config.annotation.*;
import org.springframework.web.servlet.HandlerMapping;
import java.util.Map;

/** 统一认证及资源归属检查，所有会话子路由（含SSE和评测）共用同一道门。 */
@Configuration
public class AccountWebConfiguration implements WebMvcConfigurer {
    private final IAccountService accounts;
    public AccountWebConfiguration(IAccountService accounts) { this.accounts = accounts; }
    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AsyncHandlerInterceptor() {
            @Override public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                AccountContext.clear();
                // 预检不读取私有数据；注册、登录和幂等退出可以不持有有效登录态。
                if ("OPTIONS".equals(request.getMethod())) return true;
                String path = request.getRequestURI().substring(request.getContextPath().length());
                if (path.equals("/api/v1/auth/register") || path.equals("/api/v1/auth/login") || path.equals("/api/v1/auth/logout")) return true;
                try {
                    AccountContext.bind(accounts.authenticate(bearer(request.getHeader("Authorization"))));
                    // 使用Spring解析后的路径变量，不手工切分URI，避免新增路由漏掉归属检查。
                    Object variables = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
                    if (variables instanceof Map<?, ?> map && map.get("sessionId") instanceof String sessionId)
                        accounts.requireSessionOwner(sessionId);
                    return true;
                } catch (RuntimeException e) { AccountContext.clear(); throw e; }
            }
            @Override public void afterCompletion(HttpServletRequest req, HttpServletResponse res, Object handler, Exception ex) {
                AccountContext.clear();
            }
            @Override public void afterConcurrentHandlingStarted(HttpServletRequest req, HttpServletResponse res, Object handler) {
                // SSE转异步后原线程即归还线程池，不能等连接断开才清身份。
                AccountContext.clear();
            }
        }).addPathPatterns("/api/v1/**");
    }
    public static String bearer(String header) {
        if (header == null || !header.startsWith("Bearer ") || header.substring(7).isBlank())
            throw new AppException("unauthorized", "请先登录");
        return header.substring(7);
    }
}

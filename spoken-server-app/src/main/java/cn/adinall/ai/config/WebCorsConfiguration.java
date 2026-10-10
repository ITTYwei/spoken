package cn.adinall.ai.config;

import cn.adinall.ai.domain.business.shared.adapter.port.IAppConfigPort;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS 配置。
 *
 * 放在启动模块而不是触发层：它是「部署形态」的事（谁允许跨域访问这个进程），
 * 不是接口契约的一部分 —— 同一组 Controller 在内网部署时可能压根不需要它。
 * 允许的来源从配置端口拿，所以只改 application.yml 就能调整。
 */
@Configuration(proxyBeanMethods = false)
public class WebCorsConfiguration implements WebMvcConfigurer {

    private final IAppConfigPort appConfig;

    public WebCorsConfiguration(IAppConfigPort appConfig) {
        this.appConfig = appConfig;
    }

    /**
     * 注册 CORS 规则：把 /api/** 按 spoken-ai.cors-allowed-origins 放行。
     * 流程：Spring MVC 启动时回调本方法 -> 允许列表里的来源使用 GET/POST/PUT/DELETE/OPTIONS 和任意请求头 ->
     * 不带 Cookie（allowCredentials=false），预检结果缓存 1 小时。
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns(appConfig.corsAllowedOrigins().toArray(String[]::new))
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(false)
                .maxAge(3600);
    }
}
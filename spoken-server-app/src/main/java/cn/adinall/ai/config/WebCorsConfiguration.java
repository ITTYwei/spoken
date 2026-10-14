package cn.adinall.ai.config;

import cn.adinall.ai.domain.shared.adapter.port.IAppConfigPort;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS 配置（把 /api/** 按配置里的来源放行）
 * <p>
 * 功能：声明「哪些来源可以跨域访问这个进程」；允许的来源从配置端口拿，所以只改 application.yml 就能调整。
 * 放在启动模块而不是触发层：它是「部署形态」的事（谁允许跨域访问这个进程），
 * 不是接口契约的一部分 —— 同一组 Controller 在内网部署时可能压根不需要它。
 * <pre>
 *   addCorsMappings(registry)  Spring MVC 启动时回调，注册 /api/** 的跨域规则
 * </pre>
 * 运行过程：
 * <pre>
 *   Spring MVC 启动
 *        |
 *        v
 *   addCorsMappings(CorsRegistry)
 *        |
 *        | (1) appConfig.corsAllowedOrigins() 取允许来源（对应 spoken-ai.cors-allowed-origins）
 *        | (2) registry.addMapping("/api/**")
 *        |        .allowedOriginPatterns(来源数组)
 *        |        .allowedMethods("GET","POST","PUT","DELETE","OPTIONS")
 *        |        .allowedHeaders("*")
 *        |        .allowCredentials(false)   不带 Cookie
 *        |        .maxAge(3600)             预检结果缓存 1 小时
 *        v
 *   浏览器的预检与实际请求按这套规则放行或拒绝
 * </pre>
 * 案例：
 * <pre>
 *   输入: spoken-ai.cors-allowed-origins=[http://localhost:5173]
 *         App 从 http://localhost:5173 发起 GET /api/v1/catalog/models
 *   处理: 来源命中允许列表，返回 Access-Control-Allow-Origin: http://localhost:5173
 *   输出: 预检（OPTIONS）结果被浏览器缓存 1 小时；实际请求正常拿到 JSON
 *         来源不在列表里: 服务端照常处理，浏览器侧拒绝把响应交给脚本（CORS 是浏览器行为）
 *         未配置该项: 允许来源为空数组，等价于不放行任何跨域来源
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>用 allowedOriginPatterns 而不是 allowedOrigins：前者支持通配（如 https://*.example.com），
 *       也不受「allowCredentials=true 时不能写 * 」的限制。</li>
 *   <li>allowCredentials(false)：本服务不依赖跨域 Cookie，所以不带凭证；
 *       将来若要带 Cookie，必须把来源收窄成明确白名单，不能再留通配。</li>
 *   <li>@Configuration(proxyBeanMethods = false)：本类不被其它 @Bean 方法调用，
 *       关掉 CGLIB 代理少一层无意义的包装。</li>
 *   <li>来源走配置端口（IAppConfigPort）而不是硬编码：同一份代码在内网 / 公网部署时
 *       只改 yml，不用重新打包。</li>
 *   <li>注意 AgentServiceController 类上还有 @CrossOrigin(origins = "*")：
 *       两套 CORS 规则并存，收窄放行范围时要一起确认。</li>
 * </ul>
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
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(false)
                .maxAge(3600);
    }
}

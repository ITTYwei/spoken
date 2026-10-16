package cn.adinall.ai.trigger;

import java.lang.reflect.Method;
import org.springframework.beans.factory.config.BeanDefinition;

import org.junit.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * <b>功能</b>：验证实际 Controller 路由已移除脚手架和无分页历史入口，保留 Android 使用的正式协议。
 * <p><b>体系架构 / 运行过程</b></p>
 * <pre>controller classpath scan -> merged mapping annotations -> public route contract</pre>
 * <p><b>案例</b>：旧 /api/v1/chat_stream 不在注册集合；/api/v1/chat-history/sessions/{sessionId}/turns 仍存在。</p>
 * <b>设计要点</b>
 * <ul><li>扫描全部 HTTP 控制器，防止只在测试配置中漏注册旧类而假通过。</li><li>不启动数据库、模型或媒体连接。</li></ul>
 */
public class ApiRouteContractTest {
    @Test public void onlyCanonicalConversationAndHistoryRoutesRemain() throws Exception {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        Set<String> paths = new HashSet<>();
        for (BeanDefinition definition : scanner.findCandidateComponents("cn.adinall.ai.trigger.http")) {
            Class<?> type = Class.forName(definition.getBeanClassName());
            RequestMapping root = AnnotatedElementUtils.findMergedAnnotation(type, RequestMapping.class);
            for (Method method : type.getDeclaredMethods()) {
                RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                if (mapping == null) continue;
                for (String prefix : values(root)) for (String suffix : values(mapping))
                    paths.add((prefix + "/" + suffix).replaceAll("/+", "/").replaceAll("/$", ""));
            }
        }
        for (String old : Set.of("/api/v1/query_ai_agent_config_list", "/api/v1/create_session",
                "/api/v1/chat", "/api/v1/chat_stream", "/api/v1/training/translate",
                "/api/v1/sessions/{sessionId}/turns", "/api/v1/sessions/{sessionId}/say",
                "/api/v1/sessions/{sessionId}/pronunciation", "/api/v1/pronunciation/languages",
                "/api/v1/pronunciation/health", "/api/v1/pronunciation/speech2text",
                "/api/v1/pronunciation/phonemes", "/api/v1/pronunciation/tts")) assertFalse("Legacy route: " + old, paths.contains(old));
        for (String current : Set.of("/api/v1/sessions", "/api/v1/sessions/{sessionId}/voice",
                "/api/v1/sessions/{sessionId}/events", "/api/v1/sessions/{sessionId}/turns/{turnId}/translation",
                "/api/v1/chat-history/sessions", "/api/v1/chat-history/sessions/{sessionId}",
                "/api/v1/chat-history/sessions/{sessionId}/turns", "/api/v1/catalog/avatars",
                "/api/v1/catalog/models", "/api/v1/catalog/capabilities", "/api/v1/catalog/scenarios",
                "/api/v1/home", "/api/v1/me/english-level", "/api/v1/sessions/{sessionId}/speak")) assertTrue("Missing route: " + current, paths.contains(current));
    }

    private static String[] values(RequestMapping mapping) {
        return mapping == null || mapping.value().length == 0 ? new String[]{""} : mapping.value();
    }
}

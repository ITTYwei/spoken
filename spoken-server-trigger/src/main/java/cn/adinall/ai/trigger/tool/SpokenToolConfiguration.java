package cn.adinall.ai.trigger.tool;

import org.springframework.context.annotation.*;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

/** 沿脚手架本地 Provider Bean 注册，ChatModelNode 按 YAML 收集，不另建 ADK Runner。 */
@Configuration
public class SpokenToolConfiguration {
    /** Bean 名称与 YAML local.name 一致，工具 Schema 自动排除 ToolContext 身份数据。 */
    @Bean("spokenPracticeTools")
    public ToolCallbackProvider spokenPracticeToolProvider(SpokenPracticeTools tools) {
        return MethodToolCallbackProvider.builder().toolObjects(tools).build();
    }
}

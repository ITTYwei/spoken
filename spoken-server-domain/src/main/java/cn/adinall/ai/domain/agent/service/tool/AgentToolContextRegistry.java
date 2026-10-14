package cn.adinall.ai.domain.agent.service.tool;

import cn.adinall.ai.domain.agent.model.valobj.AgentInvocationVO;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * ADK 与 Spring AI 之间的可信上下文传输适配辅助。每轮随机能力标识只放 SYSTEM，
 * 在模型请求前剥离，转为工具专用 ToolContext。模型既看不到账号，也不能自行选择能力标识。
 * 注册内容在完成、错误和取消时释放；只解析 SystemMessage，不从用户、历史或工具结果读取标记。
 * 设计原因：ADK/RxJava 与 Reactor 可能切换线程，仅用 HTTP ThreadLocal 会发生账号丢失或串号。
 */
public final class AgentToolContextRegistry {
    /**
     * 标识格式固定且唯一；不匹配用户文本中伪造的账号参数。
     */
    private static final Pattern MARKER = Pattern.compile("(?m)^SERVER_TOOL_CONTEXT=([a-f0-9]{32})$");
    /** 纯文本旁路的服务端标记；翻译没有业务身份，必须禁止继承共享模型的业务工具。 */
    private static final Pattern TEXT_ONLY = Pattern.compile("(?m)^SERVER_TOOLS_DISABLED=true$");

    /** 由业务调用方注入系统策略，转换前剥离，不是给模型读取的自然语言指令。 */
    public static final String TEXT_ONLY_POLICY = "\nSERVER_TOOLS_DISABLED=true";
    /**
     * 仅活跃模型回合持有，正常结束和取消必须 remove。
     */
    private static final Map<String, AgentInvocationVO> ACTIVE = new ConcurrentHashMap<>();
    /**
     * 工具上下文的内部键，不注册为模型输入 schema。
     */
    public static final String KEY = "spoken.trustedInvocation";

    private AgentToolContextRegistry() {
    }

    /**
     * 创建不可预测的本轮标识；标识不是身份本身。
     */
    public static String register(AgentInvocationVO context) {
        String id = UUID.randomUUID().toString().replace("-", "");
        ACTIVE.put(id, context);
        return id;
    }

    /**
     * 即使流异常也要撤销标识；已拿到上下文的迟到工具仍会因 close 失败。
     */
    public static void release(String id) {
        var context = ACTIVE.remove(id);
        if (context != null) context.close();
    }

    /**
     * 只信任 ADK 注入的系统段，剥离标识后传入 Spring AI 非模型参数。
     * 通用未授权聊天没有标识时保持原 Prompt；显式纯文本旁路禁用全部工具执行。
     */
    public static Prompt attach(Prompt prompt) {
        AgentInvocationVO context = null;
        boolean textOnly = false;
        var messages = new ArrayList<Message>();
        for (var message : prompt.getInstructions()) {
            if (message instanceof SystemMessage) {
                var matcher = MARKER.matcher(message.getText());
                while (matcher.find()) {
                    var found = ACTIVE.get(matcher.group(1));
                    if (found == null || !found.active()) throw new IllegalStateException("tool_context_expired");
                    if (context != null && context != found) throw new IllegalStateException("ambiguous_tool_context");
                    context = found;
                }
                var textMatcher = TEXT_ONLY.matcher(matcher.replaceAll(""));
                if (textMatcher.find()) textOnly = true;
                messages.add(new SystemMessage(textMatcher.replaceAll("")));
            } else messages.add(message);
        }
        if (context == null && !textOnly) return prompt;
        // ADK 转换器可能返回普通 ChatOptions，而非支持工具的模型参数；按现有 OpenAI 装配类型转换，保留公共采样配置。
        var options = prompt.getOptions() == null ? OpenAiChatOptions.builder().build() :
                prompt.getOptions() instanceof ToolCallingChatOptions ? prompt.getOptions().copy() :
                        org.springframework.ai.model.ModelOptionsUtils.copyToTarget(prompt.getOptions(), org.springframework.ai.chat.prompt.ChatOptions.class, OpenAiChatOptions.class);
        var toolOptions = (ToolCallingChatOptions) options;
        var values = new HashMap<>(toolOptions.getToolContext());
        if (context != null) values.put(KEY, context);
        toolOptions.setToolContext(values);
        if (textOnly || context.practiceInput()) {
            // 跟读/角色练习是教学素材，绝不能因“I like...”保存偏好或查询个人资料。
            // 请求级 none 覆盖装配时的默认工具选择；清空本轮回调但不修改共享模型配置。
            toolOptions.setToolCallbacks(List.of());
            toolOptions.setToolNames(Set.of());
            // none 是给模型的选择约束；此开关阻止供应商违约返回工具调用时在本机执行。
            // Spring AI 会合并默认回调，不能只依赖清空本轮列表来保护后台或跟读。
            toolOptions.setInternalToolExecutionEnabled(false);
            if (options instanceof OpenAiChatOptions openAi) openAi.setToolChoice("none");
            else throw new IllegalStateException("practice_tool_options_unsupported");
        }
        return new Prompt(messages, options);
    }
}

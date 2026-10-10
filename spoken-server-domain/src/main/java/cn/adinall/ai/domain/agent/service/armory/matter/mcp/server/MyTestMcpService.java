package cn.adinall.ai.domain.agent.service.armory.matter.mcp.server;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Service;

/**
 * 本地 MCP 工具服务（local 形态的「工具提供方」示例：一个方法就是一个工具）
 * <p>
 * 功能：把普通 Spring bean 的方法暴露成模型可调用的工具 —— 本类目前只有一个能力：
 * 把英文单词转成大写。
 * <pre>
 *   toUpperCase(XxxRequest)  @Tool 标注的工具方法：输入 word，输出 content（大写结果）
 *   XxxRequest               入参对象：word（必填，工具 schema 由注解生成）
 *   XxxResponse              出参对象：content
 * </pre>
 * 体系架构（本类怎么变成一个挂到模型上的工具）：
 * <pre>
 *   Application.testTools(MyTestMcpService)
 *        |  @Bean("myToolCallbackProvider")
 *        v
 *   MethodToolCallbackProvider.builder().toolObjects(testService).build()
 *        |
 *        v
 *   Spring 容器: bean "myToolCallbackProvider" (ToolCallbackProvider)
 *        |
 *        v
 *   demo.yml: tool-mcp-list[].local.name = "myToolCallbackProvider"
 *        |
 *        v
 *   DefaultMcpClientFactory --> LocalToolMcpCreateService.getToolCallbacks()
 *        |
 *        v
 *   ChatModelNode: OpenAiChatOptions.toolCallbacks(...)  模型可以调用 toUpperCase
 * </pre>
 * 案例：
 * <pre>
 *   输入: 模型发起 tool call，参数 JSON = {"word":"good"}
 *         即 XxxRequest{word="good"}
 *   处理: request.getWord().toUpperCase()
 *   输出: XxxResponse{content="GOOD"}
 *   边界: word 为 null 时 request.getWord().toUpperCase() 抛 NullPointerException
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>@Tool 的 description 和 @JsonPropertyDescription 是工具 schema 的唯一来源：
 *       它们是写给模型看的，改描述等于改模型「什么时候调这个工具」的判断，不要随手改。</li>
 *   <li>本类只是普通 @Service，不含任何 MCP 协议代码 —— 把它变成工具的是 Application 里的
 *       MethodToolCallbackProvider；bean 名（myToolCallbackProvider）必须与 yml 的 local.name 一致。</li>
 *   <li>入参/出参用嵌套静态类而不是 Map：字段名即工具 schema 的字段名，
 *       以声明方式约束了模型能传什么、会拿到什么。</li>
 *   <li>@JsonInclude(NON_NULL) 让 null 字段不出现在 JSON 里，
 *       避免模型看到一堆空值字段而误判参数含义。</li>
 * </ul>
 */
@Slf4j
@Service
public class MyTestMcpService {

    @Tool(description = "小写字母转换为大写字母")
    public XxxResponse toUpperCase(XxxRequest request) {
        XxxResponse xxxResponse = new XxxResponse();
        xxxResponse.setContent(request.getWord().toUpperCase());
        return xxxResponse;
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class XxxRequest {
        @JsonProperty(required = true, value = "word")
        @JsonPropertyDescription("英文单词，字符串，字母。例如: good,xiaofuge")
        private String word;
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class XxxResponse {
        @JsonProperty(required = true, value = "content")
        @JsonPropertyDescription("单词转换结果")
        private String content;
    }

}

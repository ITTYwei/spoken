package cn.adinall.ai.api.dto;

import lombok.Data;

/**
 * 智能体配置响应对象（「列智能体」接口的出参元素）
 * <p>
 * 功能：把装配期注册进 Spring 容器的智能体投影成前端可直接渲染的三元组，
 * 只有身份信息，不含运行期状态（在线与否、当前有没有会话）。
 * <pre>
 *   getAgentId()    智能体唯一标识；前端选智能体、建会话都拿它当参数
 *   getAgentName()  展示名
 *   getAgentDesc()  一句话描述
 * </pre>
 * 运行过程：
 * <pre>
 *   GET /api/v1/query_ai_agent_config_list
 *        |
 *        v
 *   AgentServiceController.queryAiAgentConfigList()
 *        |
 *        | (1) chatService.queryAiAgentConfigList() --> List&lt;AiAgentConfigTableVO.Agent&gt;
 *        | (2) 逐个 new AiAgentConfigResponseDTO 并 setAgentId / setAgentName / setAgentDesc
 *        v
 *   Response.data = [ {"agentId":"3001","agentName":"对话助手","agentDesc":"..."} ]
 * </pre>
 * 案例：
 * <pre>
 *   输入: 无（GET，不带参数）
 *   处理: stream().map(...) 把领域对象 Agent 的三个字段搬过来，Collectors.toList() 收口
 *   输出: {"code":"0000","info":"成功","data":[
 *           {"agentId":"3001","agentName":"对话助手","agentDesc":"通用问答智能体"}]}
 *         一个智能体都没装配时: data=[]，code 仍是 "0000"
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>专门建 DTO 而不是直接返领域对象 Agent：接口层不反向依赖领域层，
 *       也不会把配置里的 apiKey / baseUrl 这类敏感字段顺带漏给前端。</li>
 *   <li>纯投影对象（@Data 生成 getter/setter 供 Controller 组装）：没有校验、没有状态流转，
 *       所以后续加字段不会影响已有调用方。</li>
 *   <li>空列表是合法结果而不是错误：应用可以先起来、智能体后配，
 *       前端要按「暂时没有」渲染，不能当失败弹错误。</li>
 * </ul>
 *
 * @author 魏玉石
 * 2026/1/20 08:18
 */
@Data
public class AiAgentConfigResponseDTO {

    /**
     * 智能体ID
     */
    private String agentId;

    /**
     * 智能体名称
     */
    private String agentName;

    /**
     * 智能体描述
     */
    private String agentDesc;

}

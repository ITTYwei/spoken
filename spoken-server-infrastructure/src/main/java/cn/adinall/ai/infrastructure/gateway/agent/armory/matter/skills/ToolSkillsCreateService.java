package cn.adinall.ai.infrastructure.gateway.agent.armory.matter.skills;

import cn.adinall.ai.infrastructure.config.agent.AiAgentConfigTableVO;
import org.springframework.ai.tool.ToolCallback;

/**
 * 工具 Skills 构建服务接口（把技能目录/技能资源变成模型可调用的工具）
 * <p>
 * 功能：定义一个能力 —— 按 toolSkills 配置（type + path）造出 Skills 工具回调。
 * <pre>
 *   buildToolCallback(ToolSkills)  按 type 选 directory / resource 两种加载方式，返回 ToolCallback[]
 * </pre>
 * 体系架构（配置 -> 接口 -> 实现 -> 模型）：
 * <pre>
 *   yml: module.chat-model.tool-skills-list = [{type: resource, path: agent/skills}]
 *        |
 *        v
 *   ChatModelNode.doApply(): for toolSkills in toolSkillsList
 *        |
 *        v
 *   ToolSkillsCreateService  <-- 本接口
 *        |
 *        v
 *   DefaultToolSkillsCreateService   用 SkillsTool 造回调（directory 或 resource 分支）
 *        |
 *        v
 *   ToolCallback[] --> OpenAiChatOptions.toolCallbacks(...)  与 MCP 工具平铺在同一个列表
 * </pre>
 * 案例：
 * <pre>
 *   输入: toolSkills = {type="resource", path="agent/skills"}
 *   处理: ChatModelNode 注入的实现是 DefaultToolSkillsCreateService，
 *         调 buildToolCallback(toolSkills) --> SkillsTool 加载 classpath 下的技能资源
 *   输出: ToolCallback[]（本工程里长度为 1；type 不是 directory/resource 时为空数组）
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>与 TooMcpCreateService 分开定义：MCP 是「连外部工具源」，Skills 是「读本地技能书」，
 *       两条来源各自演进，ChatModelNode 只负责把结果合并。</li>
 *   <li>接口只有一个方法，当前唯一实现是 DefaultToolSkillsCreateService；
 *       要再加一种加载方式（如 Git 仓库）时新增实现并替换注入即可，调用方不用改。</li>
 *   <li>方法带 throws Exception：加载目录/资源是 IO 操作，失败要在装配期暴露。</li>
 * </ul>
 *
 * @author 魏玉石
 * 2026/2/6 08:03
 */
public interface ToolSkillsCreateService {

    /** 将工具配置构建为模型可调用的工具回调。 */
    ToolCallback[] buildToolCallback(AiAgentConfigTableVO.Module.ChatModel.ToolSkills toolSkills) throws Exception;

}

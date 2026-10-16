package cn.adinall.ai.infrastructure.gateway.agent.armory.matter.skills.impl;

import cn.adinall.ai.infrastructure.config.agent.AiAgentConfigTableVO;
import cn.adinall.ai.infrastructure.gateway.agent.armory.matter.skills.ToolSkillsCreateService;
import lombok.extern.slf4j.Slf4j;
import org.springaicommunity.agent.tools.SkillsTool;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Skills 工具构建服务默认实现（Spring AI Community 构建 skills
 * <a href="https://github.com/spring-ai-community/spring-ai-agent-utils">spring-ai-agent-utils</a>）
 * <p>
 * 功能：按 toolSkills.type 走 directory 或 resource 分支，用 SkillsTool 造出工具回调。
 * <pre>
 *   buildToolCallback(toolSkills)  type="directory" --> SkillsTool.addSkillsDirectory(path)
 *                                  type="resource"  --> SkillsTool.addSkillsResource(ClassPathResource(path))
 * </pre>
 * 运行过程：
 * <pre>
 *   ChatModelNode.doApply(): for toolSkills in module.chatModel.toolSkillsList
 *        |
 *        v
 *   buildToolCallback(toolSkills)
 *        |
 *        | (1) type = toolSkills.getType(); path = toolSkills.getPath()
 *        |
 *        | (2) type 等于 "directory"
 *        |        |
 *        |        v
 *        |     SkillsTool.builder().addSkillsDirectory(path).build() --> 加进 toolCallbackList
 *        |
 *        | (3) type 等于 "resource"
 *        |        |
 *        |        v
 *        |     SkillsTool.builder().addSkillsResource(new ClassPathResource(path)).build() --> 加进列表
 *        |
 *        | (4) 两个分支都没命中 --> 返回空数组（不抛异常）
 *        v
 *   ToolCallback[] --> ChatModelNode 用 List.of(...) 平铺进统一的 toolCallbackList
 * </pre>
 * 案例：
 * <pre>
 *   输入: toolSkills = {type="resource", path="agent/skills"}
 *   处理: 命中 resource 分支 --> new ClassPathResource("agent/skills") --> SkillsTool 加载
 *   输出: ToolCallback[1]
 *   另一例: toolSkills = {type="directory", path="D:/skills"} --> ToolCallback[1]（读文件系统目录）
 *   失败例: toolSkills = {type="skill", path="agent/skills"} --> ToolCallback[0]（空数组，且不报错）
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>两个分支写成并列的 if 而不是 if/else if：type 是单值字符串，最多命中一个，
 *       这样写只是可读性选择，不会产出两个回调。</li>
 *   <li>type 只认字面量 "directory" 和 "resource"，且判断时把常量写在 equals 前面，不会 NPE；
 *       其他取值静默返回空数组 —— 这是本类最容易踩的坑：配置写错不报错，只是少一个工具。</li>
 *   <li>ToolSkills.type 的默认值是 "directory"：yml 里不写 type 就走目录加载，
 *       此时 path 必须是一个真实存在的目录。</li>
 *   <li>返回数组而不是 List：与 MCP 那条来源保持同一签名，
 *       方便 ChatModelNode 一视同仁地合并两条来源。</li>
 * </ul>
 *
 * @author 魏玉石
 * 2026/2/6 08:04
 */
@Slf4j
@Service
public class DefaultToolSkillsCreateService implements ToolSkillsCreateService {

    /** 将工具配置构建为模型可调用的工具回调。 */
    @Override
    public ToolCallback[] buildToolCallback(AiAgentConfigTableVO.Module.ChatModel.ToolSkills toolSkills) throws Exception {

        String type = toolSkills.getType();
        String path = toolSkills.getPath();

        List<ToolCallback> toolCallbackList = new ArrayList<>();

        if ("directory".equals(type)){
            ToolCallback toolCallback = SkillsTool.builder()
                    .addSkillsDirectory(path)
                    .build();
            toolCallbackList.add(toolCallback);
        }

        if ("resource".equals(type)){
            ToolCallback toolCallback = SkillsTool.builder()
                    .addSkillsResource(new ClassPathResource(path))
                    .build();
            toolCallbackList.add(toolCallback);
        }

        return toolCallbackList.toArray(new ToolCallback[0]);
    }

}

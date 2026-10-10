package cn.adinall.ai.domain.agent.model.entity;

import cn.adinall.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 装配命令（装配链的入参载体）
 * <p>
 * 功能：把「一张智能体配置表」包成装配链节点统一的入参类型。
 * 链上每个节点都只读 requestParameter.aiAgentConfigTableVO，过程数据一律写在
 * DynamicContext 里 —— 命令只承载输入，不承载中间态。
 * <pre>
 *   aiAgentConfigTableVO  待装配的智能体配置表（appName + agent + module）
 * </pre>
 * 运行过程（在链上的传递）：
 * <pre>
 *   ArmoryService.acceptArmoryAgents(tables)
 *        |
 *        | ArmoryCommandEntity.builder().aiAgentConfigTableVO(table).build()
 *        v
 *   RootNode.apply(command, new DynamicContext())
 *        |        |
 *        |        +--> doApply 里 requestParameter.getAiAgentConfigTableVO()
 *        v
 *   AiApiNode -> ChatModelNode -> AgentNode -> AgentWorkflowNode -> RunnerNode
 *        （同一个 command 实例沿链被所有节点读取，没有任何节点改写它）
 * </pre>
 * 案例：
 * <pre>
 *   输入: table = AiAgentConfigTableVO{appName="demo-app", agent={agentId="3001"},
 *                   module={aiApi={baseUrl="https://api.deepseek.com"},
 *                           chatModel={model="deepseek-chat"},
 *                           agents=[{name="main-agent"}]}}
 *   处理: ArmoryCommandEntity.builder().aiAgentConfigTableVO(table).build()
 *   输出: ArmoryCommandEntity{aiAgentConfigTableVO=table}；Lombok 的 Data 提供 getter/setter，
 *         Builder 供装配入口构造，AllArgsConstructor/NoArgsConstructor 供序列化/测试使用
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>命令只装输入：节点不该在命令上挂中间结果，否则「谁改了入参」会变成难查的隐性依赖；
 *       中间态统一走 DynamicContext。</li>
 *   <li>用 @Builder 而不是多参构造：后续若新增装配入参（如租户、版本号），
 *       已有节点调用点不用改。</li>
 *   <li>一个命令对应一次装配：ArmoryService 每个配置表都 new 一个命令 + 一个上下文，
 *       保证多次装配互不串味。</li>
 * </ul>
 *
 * @author 魏玉石
 * 2025/12/17 08:15
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ArmoryCommandEntity {

    private AiAgentConfigTableVO aiAgentConfigTableVO;

}

package cn.adinall.ai.domain.agent.service;

import cn.adinall.ai.domain.agent.model.valobj.AiAgentConfigTableVO;

import java.util.List;

/**
 * 智能体装配服务接口（装配能力的领域端口）
 * <p>
 * 功能：只声明一件事 —— 接收一批智能体配置表并完成装配。
 * 调用方（AiAgentAutoConfig）依赖接口而不依赖实现，装配细节被关在领域服务里。
 * <pre>
 *   acceptArmoryAgents(tables)  批量装配：一张配置表装配出一只可运行的智能体
 * </pre>
 * 运行过程（调用链）：
 * <pre>
 *   AiAgentAutoConfig.onApplicationEvent(ApplicationReadyEvent)
 *        |
 *        | (1) 未启用 / tables 为空 --> 直接 return（数字人能力可独立启动）
 *        | (2) armoryService.acceptArmoryAgents(new ArrayList&lt;&gt;(tables.values()))
 *        v
 *   IArmoryService  <-- 本接口
 *        |
 *        v
 *   ArmoryService.acceptArmoryAgents()
 *        |
 *        | for table in tables:
 *        |     RootNode.apply(ArmoryCommandEntity{table}, new DynamicContext())
 *        v
 *   Spring 容器新增 bean: agentId -> AiAgentRegisterVO
 * </pre>
 * 案例：
 * <pre>
 *   输入: tables = [AiAgentConfigTableVO{appName="demo-app", agent={agentId="3001"}}]
 *   处理: 实现类遍历并驱动装配链
 *   输出: void；副作用是容器里出现 "3001" bean。
 *         装配失败时抛 Exception（实现里常见 AppException，例如 ILLEGAL_PARAMETER）
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>接口方法 throws Exception：装配链涉及反射注册 bean、MCP 连接等会抛受检异常的操作，
 *       强制调用方显式处理，避免启动期装配失败被静默吞掉。</li>
 *   <li>入参是「配置表列表」而不是单个智能体：启动期一次性装配所有配置，
 *       调用方不必自己写循环；同时也方便未来做增量/热更新装配。</li>
 *   <li>没有返回值：装配产物以 agentId 为名进入 Spring 容器，取用走
 *       DefaultArmoryFactory.getAiAgentRegisterVO(agentId)，接口不必把产物再传一遍。</li>
 * </ul>
 *
 * @author 魏玉石
 * 2025/12/17 08:13
 */
public interface IArmoryService {

    /**
     * 批量装配智能体：一张配置表装配出一只可运行的智能体。
     *
     * 流程：逐个配置表起一条装配责任链（每个配置表一个独立的 DynamicContext，互不共用），
     * 链尾 RunnerNode 把装配产物以 agentId 为 bean 名注册进 Spring 容器。
     * 本方法**只能在启动期调用**（AiAgentAutoConfig 在 ApplicationReadyEvent 里触发）：
     * 它不做增量合并，重复装配同一 agentId 会覆盖容器里的旧 bean。
     *
     * @param tables 待装配的智能体配置表；为空集合时什么都不做（实现层 for 循环自然不会执行）
     * @throws Exception 装配链节点抛出的异常（如 Runner 的 runner.agentName 为空抛 ILLEGAL_PARAMETER、
     *                   MCP/插件按名字取 bean 失败等）。都是受检异常，直接向上抛给启动流程，
     *                   不做降级 —— 启动期装配失败必须让应用起不来
     */
    void acceptArmoryAgents(List<AiAgentConfigTableVO> tables) throws Exception;

}

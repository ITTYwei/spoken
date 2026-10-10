package cn.adinall.ai.domain.agent.model.valobj;

import com.google.adk.runner.InMemoryRunner;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 智能体注册值对象（装配产物，运行期的唯一入口句柄）
 * <p>
 * 功能：承载「一只装配完成的智能体」的全部运行期要素 —— 身份信息 + 可执行的 Runner。
 * 该对象在装配期由 RunnerNode 以 agentId 为 bean 名注册进 Spring 容器，
 * 运行期由 ChatService 按 agentId 取回后直接 runner.runAsync(...) 发起对话。
 * <pre>
 *   appName    应用名，ADK session 归属的 app（建会话时传给 sessionService）
 *   agentId    智能体 ID，同时是 Spring bean 名（装配期与运行期的契约）
 *   agentName  智能体显示名（来自配置 agent.agentName，非根智能体名）
 *   agentDesc  智能体描述（仅展示/排障用，不参与运行）
 *   runner     ADK 的 InMemoryRunner：runAsync / sessionService 都在它身上
 * </pre>
 * 体系架构（装配期写入 -> 运行期读取）：
 * <pre>
 *   装配期 RunnerNode
 *        |
 *        | AiAgentRegisterVO.builder()
 *        |     .appName("demo-app").agentId("3001")
 *        |     .agentName("对话助手").agentDesc("口语陪练")
 *        |     .runner(new InMemoryRunner(pipeline, "demo-app", plugins))
 *        v
 *   registerBean("3001", AiAgentRegisterVO.class, vo)   -- bean 名 = agentId
 *        |
 *        v
 *   运行期 ChatService
 *        | defaultArmoryFactory.getAiAgentRegisterVO("3001")
 *        v
 *   runner.sessionService().createSession(appName, userId)   --> sessionId
 *   runner.runAsync(userId, sessionId, Content.fromParts(...)) --> Flowable&lt;Event&gt;
 * </pre>
 * 案例：
 * <pre>
 *   输入: 装配配置 appName="demo-app", agent={agentId="3001", agentName="对话助手",
 *         agentDesc="口语陪练"}, runner.agentName="pipeline"
 *   处理: RunnerNode 造 Runner 并 builder 出该对象 -> registerBean
 *   输出: AiAgentRegisterVO{appName="demo-app", agentId="3001", agentName="对话助手",
 *         agentDesc="口语陪练", runner=InMemoryRunner}；
 *         取不到（未装配 / agentId 写错）时 getBean 抛 NoSuchBeanDefinitionException
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>只读（@Getter + @Builder + 全参/无参构造）：装配产物一旦注册就不该被运行期改写，
 *       否则多线程对话会共享一个被改动的状态。</li>
 *   <li>把 Runner 直接放进 VO：运行期只依赖这一个对象，不需要再持有工厂/配置，
 *       松耦合到「一个 id 换一个可执行体」。</li>
 *   <li>appName 必须与配置一致：ADK 的 sessionService 按 appName 隔离会话，
 *       换 appName 等于换了一套会话空间。</li>
 * </ul>
 *
 * @author 魏玉石
 * 2025/12/17 08:19
 */
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AiAgentRegisterVO {

    /**
     * 智能体名称
     */
    private String appName;

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

    /**
     * 智能体执行对象
     */
    private InMemoryRunner runner;

}

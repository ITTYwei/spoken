package cn.adinall.ai.domain.agent.model.valobj.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 智能体工作流类型枚举（配置里的 type 字符串与装配节点 bean 名之间的字典）
 * <p>
 * 功能：把 yml 中 module.agent-workflows[].type 的字符串，翻译成装配链下一个节点的 bean 名。
 * <pre>
 *   formType(type)  按 type 找枚举（忽略大小写）；找不到或入参为 null 时返回 null
 *   getName()       中文名，仅用于日志/展示
 *   getType()       配置里写的字符串：loop / parallel / sequential
 *   getNode()       对应的装配节点 bean 名：loopAgentNode / parallelAgentNode / sequentialAgentNode
 * </pre>
 * 体系架构（配置 -> 枚举 -> 节点）：
 * <pre>
 *   yml: module.agent-workflows[].type = "loop" | "parallel" | "sequential"
 *        |
 *        v
 *   AgentWorkflowNode.doApply()  取出 currentAgentWorkflow（一条配置）
 *        |
 *        | AgentWorkflowNode.get()
 *        |        type = currentAgentWorkflow.getType()
 *        |        agentTypeEnum = AgentTypeEnum.formType(type)
 *        |        |        null --> throw RuntimeException("agentWorkflow type is error!")
 *        |        v
 *        |     node = agentTypeEnum.getNode()
 *        |        |
 *        |        +--> "loopAgentNode"        --> LoopAgentNode       （LoopAgent，带 maxIterations）
 *        |        +--> "parallelAgentNode"    --> ParallelAgentNode   （ParallelAgent）
 *        |        +--> "sequentialAgentNode"  --> SequentialAgentNode （SequentialAgent）
 *        |        +--> 其它（不可能）          --> RunnerNode           （default 分支兜底）
 *        v
 *   对应节点造出复合智能体，写进 DynamicContext.agentGroup
 * </pre>
 * 案例：
 * <pre>
 *   输入: type = "SEQUENTIAL"（配置里大小写混写）
 *   处理: formType("SEQUENTIAL") 逐个 equalsIgnoreCase 比对，命中 Sequential
 *   输出: AgentTypeEnum.Sequential{name="串行执行", type="sequential", node="sequentialAgentNode"}
 *         另一例: formType("pipeline") --> null --> AgentWorkflowNode 抛
 *                 RuntimeException("agentWorkflow type is error!")
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>枚举把「配置字符串」和「bean 名」绑在一起：改 bean 名只需改这一处，
 *       不必去 AgentWorkflowNode 的 switch 里找字面量。</li>
 *   <li>formType 用 equalsIgnoreCase：配置里写 Loop / LOOP 都能命中，减少大小写踩坑；
 *       但 null 直接返回 null（不做空串匹配），调用方必须处理 null。</li>
 *   <li>formType 返回 null 而不是抛异常：是否视为错误由调用方决定
 *       （AgentWorkflowNode 选择抛 RuntimeException），枚举本身保持无副作用。</li>
 *   <li>@NoArgsConstructor + 非 final 字段是 Lombok 与枚举构造的既有写法，
 *       实际使用只走带参构造的三个常量，不要依赖 setter 改常量字段。</li>
 * </ul>
 */
@Getter
@AllArgsConstructor
@NoArgsConstructor
public enum AgentTypeEnum {

    Loop("循环执行", "loop", "loopAgentNode"),
    Parallel("并行执行", "parallel", "parallelAgentNode"),
    Sequential("串行执行", "sequential", "sequentialAgentNode"),

    ;

    private String name;
    private String type;
    private String node;

    public static AgentTypeEnum formType(String type) {
        if (type == null) {
            return null;
        }

        for (AgentTypeEnum value : values()) {
            if (value.getType().equalsIgnoreCase(type)) {
                return value;
            }
        }

        return null;
    }

}

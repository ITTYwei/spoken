package cn.adinall.ai.domain.agent.model.valobj;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 智能体配置表值对象（装配链的配置源，与 yml 一一对应）
 * <p>
 * 功能：承载「一只智能体怎么装配」的全部配置，由 AiAgentAutoConfigProperties 从
 * <code>ai.agent.config.tables.*</code> 绑定进来，再被装配链各节点按需读取。
 * 结构是四层嵌套：表（本类）-> 身份 Agent + module -> module 下的
 * aiApi / chatModel / agents / agentWorkflows / runner。
 * <pre>
 *   appName                     应用名（ADK session 归属的 app）
 *   agent                       智能体身份：agentId（bean 名）/ agentName / agentDesc
 *   module.aiApi                baseUrl / apiKey / completionsPath / embeddingsPath
 *   module.chatModel            model / toolMcpList / toolSkillsList
 *   module.agents               List&lt;Agent&gt;：name / instruction / description / outputKey
 *   module.agentWorkflows       List&lt;AgentWorkflow&gt;：type(loop|parallel|sequential)
 *                               / name / subAgents / description / maxIterations
 *   module.runner               agentName（根智能体名）/ pluginNameList
 * </pre>
 * 体系架构（谁读哪一段）：
 * <pre>
 *   application.yml: ai.agent.config.tables.{key}
 *        |
 *        v
 *   AiAgentAutoConfigProperties (prefix="ai.agent.config")
 *        |
 *        v
 *   AiAgentConfigTableVO  <-- 本类
 *        |
 *        +--> AiApiNode         读 module.aiApi
 *        +--> ChatModelNode     读 module.chatModel（model / toolMcpList / toolSkillsList）
 *        +--> AgentNode         读 module.agents
 *        +--> AgentWorkflowNode 读 module.agentWorkflows
 *        +--> RunnerNode        读 module.runner + appName + agent
 * </pre>
 * 案例（与 spoken-server-app/src/main/resources/agent/spoken-server.yml 同构）：
 * <pre>
 *   输入: ai.agent.config.enabled=true
 *         tables.demo.app-name=demo-app
 *         tables.demo.agent.agent-id=3001
 *         tables.demo.agent.agent-name=对话助手
 *         tables.demo.module.ai-api.base-url=https://api.deepseek.com
 *         tables.demo.module.chat-model.model=deepseek-chat
 *         tables.demo.module.agents[0].name=main-agent
 *         tables.demo.module.runner.agent-name=main-agent
 *   处理: Spring 松散绑定（kebab-case -> 驼峰）后得到一个 AiAgentConfigTableVO 实例
 *   输出: AiAgentConfigTableVO{appName="demo-app", agent=Agent{agentId="3001"},
 *         module=Module{aiApi=AiApi{baseUrl="https://api.deepseek.com"},
 *         chatModel=ChatModel{model="deepseek-chat"},
 *         agents=[Agent{name="main-agent"}], runner=Runner{agentName="main-agent"}}}
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>本类是纯配置载体（@Data）：只有字段和嵌套静态类，不含任何行为，
 *       保证「配置长什么样」一眼可查。</li>
 *   <li>层级刻意与 yml 对齐：tables.* 的 key 只用于找表，真正决定 bean 名的是
 *       agent.agent-id，配置改 key 不会影响运行期取用。</li>
 *   <li>给默认值兜底：chatModel.toolMcp.requestTimeout=3000、agentWorkflow.maxIterations=3、
 *       aiApi.completionsPath="/v1/chat/completions"，配置里不写也能装配成功。</li>
 *   <li>装配期的类型错误（如 agentWorkflow.type 不在枚举里）会在节点里抛异常，
 *       因此这份配置的字段名/取值是本类与各节点之间的硬契约，改动需同步节点代码。</li>
 * </ul>
 *
 * @author 魏玉石
 * 2025/11/29 10:54
 */
@Data
public class AiAgentConfigTableVO {

    /**
     * 应用名称
     */
    private String appName;

    /**
     * 智能体配置
     */
    private Agent agent;

    /**
     * 智能体模块
     */
    private Module module;

    @Data
    public static class Agent {

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

    @Data
    public static class Module {

        private AiApi aiApi;

        private ChatModel chatModel;

        private List<Agent> agents;

        private List<AgentWorkflow> agentWorkflows;

        private Runner runner;

        @Data
        public static class AiApi {
            private String baseUrl;
            private String apiKey;
            private String completionsPath = "/v1/chat/completions";
            private String embeddingsPath = "/v1/embeddings";

        }

        @Data
        public static class ChatModel {

            private String model;

            private List<ToolMcp> toolMcpList;

            private List<ToolSkills> toolSkillsList;

            @Data
            public static class ToolMcp {

                private SSEServerParameters sse;

                private StdioServerParameters stdio;

                private LocalParameters local;

                @Data
                public static class SSEServerParameters {
                    private String name;
                    private String baseUri;
                    private String sseEndpoint;
                    private Integer requestTimeout = 3000;

                }

                @Data
                public static class StdioServerParameters {
                    private String name;
                    private Integer requestTimeout = 3000;
                    private ServerParameters serverParameters;

                    @Data
                    public static class ServerParameters {
                        private String command;
                        private List<String> args;
                        private Map<String, String> env;

                    }
                }

                @Data
                public static class LocalParameters {
                    private String name;
                }

            }

            @Data
            public static class ToolSkills {

                /**
                 * 类型；directory（用户配置的，映射进来的）、resource（放到工程下的）
                 */
                private String type = "directory";

                /**
                 * 路径；
                 */
                private String path;

            }

        }

        @Data
        public static class Agent {
            private String name;
            private String instruction;
            private String description;
            private String outputKey;

        }

        @Data
        public static class AgentWorkflow {
            /**
             * 类型；loop、parallel、sequential
             */
            private String type;
            private String name;
            private List<String> subAgents;
            private String description;
            private Integer maxIterations = 3;

        }

        @Data
        public static class Runner {
            private String agentName;
            private List<String> pluginNameList;
        }
    }

}

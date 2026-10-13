# spoken-server 项目长期备忘

## 代码注释风格（用户明确要求，2026-10-13）

- 简要行注释为主，**两三句代码一个注释点**，不要每句都加。
- 禁止大块注释墙（长横线框、多段展开、编号列表式的行内注释）——曾因加得过密被要求回精简。
- 方法级说明优先用 1-2 行 javadoc，不写多段 <p> 展开。
- 项目原有的重 javadoc 风格（类头大段说明）保持不动，只约束新增注释。

## 构建环境

- Maven 不在 PATH：`D:/develop/env/apache-maven-3.9.16/bin/mvn`
- JAVA_HOME：`D:/develop/evn/jdk17`
- 常用验证命令：`mvn -q -o compile -DskipTests`（离线编译约 1.5 分钟）
- ADK 版本 0.5.0，源码 jar 在 `~/.m2/repository/com/google/adk/google-adk/0.5.0/google-adk-0.5.0-sources.jar`，查 API 行为直接解压看源码。

## 提示词体系关键结论（详见 2026-10-13 日志）

- 系统提示词（yml instruction）装配期固化进常驻 Runner，运行期改配置不生效；动态化三路径：{var} 占位符 / Instruction.Provider / 重跑装配链。
- 每轮变化内容（档位/任务/语言/persona）走 ReplyPromptBuilder → 用户消息（CURRENT TURN），不要搬进系统提示词。
- 教学链路每轮新建短生命周期 ADK 会话，历史由 HistoryProvider 从 DB 重建。

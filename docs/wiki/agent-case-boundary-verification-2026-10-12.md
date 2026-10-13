# Agent 与 case 调用边界验证（2026-10-12）

参考本地 `walissh-server` 的 `IChatService`、`ChatService` 和 `AgentServiceController`，以及 `.skill-sources/xfg-ddd-skills/references/case-layer.md` 的多领域编排约定。本项目已有 case 层，因此将跨领域流程放在 case，保留 Agent 内 ADK、MCP 与模型装配原层次。

## 当前链路

```text
ConversationCaseImpl.voice
  -> IConversationService：识别与回合记录
  -> IPronunciationService：发音评测
  -> ILearnerLevelService：会话稳定档位（首句快照提供初始值）
  -> ReplyPromptBuilder：陪练业务上下文
  -> IChatService.createSession / handleTextStream：Agent 模型对话
  -> IConversationService.say：完整文本一次下发 OpenTalking
```

`ChatService.handleTextStream` 在 Agent 领域通过 `AgentTextStream` 将 ADK Event 转为文本，过滤用户回显、重复汇总与无文本事件，并传播模型错误。原始 `handleMessageStream` 保留，供工具事件和现有 Agent 接口使用。

移除 conversation 的 `IReplyGeneratorPort`、`GenerateReplyCommandEntity`、`replyForTurn`，以及 infrastructure 的 `AgentReplyGenerator`、`ReplyConfiguration`、`ReplyProperties`。case 仍读取原有 `spoken-ai.reply.agent-id`，默认值 100003，不改变模型配置。

`LearnerLevelTracker` 和 `LearnerLevelEnum` 移入 learner，`LearnerLevelService` 同步同一会话的更新，case 在关闭和设备重建时清理状态。档位仍不向界面展示。陪练提示词规则保留在 conversation，模型执行和事件解释归 Agent。

## 验证

使用 Maven 仓库 `D:\develop\env\mvn_repo` 离线构建，40 项测试通过：Agent 文本转换 6、学员档位与清理 4、语音 case 8、首句 case 6、首句规则 12、Agent 日志配置 4。覆盖整段一次播放、模型建会话失败、生成失败、播放失败、空输出、评测降级、旧 OpenTalking 链路绕过 Agent，以及首句 LOW 继承。

本次没有改变整段等待后一次 `/say` 的播放策略，所以不能据此声称聊天首音延迟下降。

启动装配检查：使用 JDK 17 的 JVM 属性在 8092 端口启动隔离实例（启动类当前未转发命令行 args，因此使用 `-Dserver.port=8092`）；禁用 Flyway。Spring、Agent 模型装配均成功，场景接口 HTTP 200。验证后已停止隔离进程；8091 原运行实例未重启，采用新代码需重启。

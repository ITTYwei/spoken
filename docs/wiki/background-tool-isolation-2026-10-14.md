# 后台观察误继承业务工具修复（2026-10-14）

## 现象与原因

用户提供 17:05:12 的 `ToolContext is required by the method as an argument`。同一时段主教练正常继续生成并结束，本轮为 RESPONDED，上一轮有问题且本轮含英文，符合 `TeachingPreparation.begin` 并发启动能力观察的条件。

后台 `AbilityEvidenceService` 直接复用装配模型，该模型默认注册了业务工具。此前仅传空 `toolCallbacks`，Spring AI 会与默认回调合并，空列表不能解除默认工具。观察不携带账号/业务会话能力上下文，模型误返回业务工具调用时，方法回调在进入业务规则前就因缺少 ToolContext 抛错。

## 处理

- 能力观察请求显式配置 `toolChoice=none` 和 `internalToolExecutionEnabled=false`。前者限制模型选择，后者阻止供应商违约返回工具调用时的客户端执行。无有效 JSON 时沿用已有丢弃证据规则。
- 字幕翻译注入服务端纯文本标记，由自有 `AgentToolContextRegistry` 剥离并转换成同样的请求级约束；不建立假账号上下文。
- 跟读补充禁止内部执行工具，保留原有 none 与业务规则校验。
- 正常带可信 AgentInvocation 的对话继续使用业务工具；不修改共享模型默认配置、ADK/Spring AI 框架或装配节点。

## 验证

`spoken-server-app/target/background-tool-isolation-tests.log`：31 项检查通过。

新增真实 Spring AI 模型加本地 HTTP 供应商替身：模型默认注册必须接收 ToolContext 的工具，接口仍返回工具调用，后台请求携带 none，工具执行次数为 0，仅一次请求，证据被丢弃。默认配置仍为 auto 且允许工具执行。

另验证字幕翻译无业务上下文时禁用工具、内部标记不出现在模型消息、跟读禁止内部执行，以及正常证据、取消释放和账号隔离。

`spoken-server-app/target/background-tool-live-regression.log`：真实配置模型通过现有脚手架完成课程选择、自由模式及明确长期目标三轮业务调用，1 项在线验收通过。合计 32 项检查通过。

## 加载

在 IDEA 重启后端后生效；手机连接保持不变，本次未操作手机会话。

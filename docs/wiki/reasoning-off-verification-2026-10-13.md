# 关闭模型推理段验证（2026-10-13）

打通「用户说完话 → 数字人开口」之间那段长时间空白。根因不在 OpenTalking，也不在 Java 的过滤逻辑，而在模型的**思考段**。

## 空白流从哪来

`bonsai-2-27b`（Ternary-Bonsai-2-27B-PQ2，llama.cpp 在 `http://172.18.0.103:8989`）是推理模型：默认整段只吐 `delta.reasoning_content`，`delta.content` 一直是 `null`。Spring AI 只认 `content`，于是每个思考块都变成一条空 ADK 事件。

真机证据（`data/log/log_info.log`）：

| 位置 | 现象 |
| --- | --- |
| `log_info.log:2203` | `11:43:18` 起，`content":{"parts":[]` 空事件约 85 条/秒连成一片 |
| `log_info.log:3489` | `Agent first delta … latencyMs=16590`（turnId=137 首字 16.6 秒） |
| `log_info.log:9486-9487` | `11:45:29` 才出现第一个 `{"text":"我可以"}`，`latencyMs=71729` |
| `log_error.log:1982` | `text_stream_append_failed`（Output closed） |

同一会话在 GPU 机的 `logs/opentalking-api-8001.log` 里：`llm_stream_ms=16550`、`first_webrtc_queue_ms=17405` —— 这段时间 OpenTalking 一条文本都没收到，只能待机（无音频、无字幕），第二轮更是 45 秒无文本触发 `external_text.pieces()` 超时，`TaskGroup` 把整轮打死。

网关直连可复现：`delta.reasoning_content` 满屏、`content` 为 `null`。

## 改动

| 文件 | 改动 |
| --- | --- |
| `agent/spoken-server.yml` | `chat-model.reasoning-effort: none` |
| `AiAgentConfigTableVO.Module.ChatModel` | 新增 `reasoningEffort` 字段（留空 = 不下发） |
| `ChatModelNode` | 非空时把 `reasoningEffort` 挂到 `OpenAiChatOptions` 默认参数上 |
| `ChatModelNodeTest` | 契约测试：非空必须进默认参数，留空/空白不得下发 |

- 挂在**模型默认参数**上而不是每轮请求上：运行期 `ChatService.handleTextStream` 拿到的 Prompt 不带 options，靠默认参数兜底；留空不下发，换回非推理模型不需要改代码。
- 为什么不用 `extraBody` / 请求体塞 `chat_template_kwargs`：当前 `spring-ai-bom:1.1.0-M3` 的 `OpenAiChatOptions` **没有** `extraBody`（1.1.8 才有），而它**已经有** `reasoningEffort` → `ChatCompletionRequest.reasoning_effort`；不为一个请求字段升级整条 AI 栈。
- llama.cpp 对 `reasoning_effort` 的处理是特例：`none` 走空思考块（`<think>\n\n</think>`），其他值原样进 Jinja 模板校验 —— 实测 `"bogus"` 直接 HTTP 500（`Unexpected reasoning effort`）。所以换 OpenAI 官方模型时要改成 `minimal/low/medium/high`。

## 验证

离线构建（Maven 仓库 `D:\develop\env\mvn_repo`，JDK 17 编译目标）跑 46 项测试，45 通过，含新增 `ChatModelNodeTest` 2 项、`MySpringAIPayloadLogTest` 2 项。

现场校验（一次性工具 `spoken-server-app/target/live-check/LiveReasoningCheck.java`，真实 `agent/spoken-server.yml` → Binder 绑到 VO → `OpenAiChatModel` → 真实网关）：

```text
YAML  baseUrl=http://172.18.0.103:8989
YAML  model=bonsai-2-27b
YAML  reasoningEffort=none

A 配置态（reasoning-effort=none）    -> 首个正文 1095 ms，正文 3 块，空白 2 块，正文=Hello there friend
B 对照（不下发 reasoning_effort）    -> 首个正文 从未出现，空白 66 块，正文为空
C 配置态 + maxTokens=64              -> 首个正文 439 ms，正文 6 块，空白 2 块
```

B 就是那 16~70 秒窗口的缩小版：思考吃掉了全部 token 预算，一个正文字符都没有。

已知未处理：`OpeningCaseTest.firstReplyUsesSameLowStartingLevelAsOpening` 失败。它与本次改动无关 —— 把本文件涉及的几处改动临时 stash 后重跑，同样 1 个 error，根因是未提交的流式改造（`ConversationCaseImpl.startReply` 走 `IConversationService.streamReply`），该测试只 mock 了旧的整段 `/say`。

## 附带：把「发给 LLM 的内容」打出来

同一层适配器（`MySpringAI`）加 `module.chat-model.log-llm-payload` 开关，本轮在 `agent/spoken-server.yml` 打开：

| 文件 | 改动 |
| --- | --- |
| `agent/spoken-server.yml` | `chat-model.log-llm-payload: true` |
| `AiAgentConfigTableVO.Module.ChatModel` | 新增 `logLlmPayload`（默认 false = 不打） |
| `AgentNode` | 装配时 `setLogLlmPayload(...)` 转交（每个 agent 一个 `MySpringAI`） |
| `MySpringAI` | 调模型前打印：请求头 + 每条 message（角色 + 完整文本，不截断） |
| `MySpringAIPayloadLogTest` | 契约两条：开关打开必须打、关闭必须不打 |

打的是 `MessageConverter.toLlmPrompt` 的产物 —— ADK 在这一步才把系统指令拼成 SystemMessage、再拼上历史消息，所以它是**模型实际看到的内容**；ADK 自带的 `logRequest` 是 DEBUG 且会截断，排查提示词时看不见全貌。

现场输出（真实网关，LOW 档 + 「I play frisbee」）：

```text
[YAML] model=bonsai-2-27b reasoningEffort=none logLlmPayload=true
12:21:32.541 INFO MySpringAI -- 发给 LLM 的请求 stream=false model=bonsai-2-27b messages=2
12:21:32.543 INFO MySpringAI -- 发给 LLM [1/2] SYSTEM:
You are SpokenCoach, a spoken-English partner for Chinese learners. Zero judgement, one question per reply, one to three short sentences.
12:21:32.544 INFO MySpringAI -- 发给 LLM [2/2] USER:
Turn context. Apply the persona rules from your system instruction.

Learner level for this reply: low
Language for this reply: CHINESE-FIRST.

What just happened:
- what the learner said (speech recognition): I play frisbee
- language they used: English

Now reply. End with exactly one question.
12:21:34.412 INFO SpringAIObservabilityHandler -- Request completed successfully: model=bonsai-2-27b, type=chat, duration=1882ms, tokens=139
[模型回复] 你说你玩 "I play frisbee"，真不错！你通常什么时候玩飞盘呢？
```

代价：系统指令约 3 KB、且随轮次增长，内容含学员原话 —— 排查完把 `log-llm-payload` 改回 `false`。

## 生效方式

模型装配在启动期完成，需重启 Java 进程（当前 8091 是 IntelliJ debug 实例，11:42 启动，未擅自重启）。重启后第一轮回复不再等待推理段，同时日志里能看到每轮发给模型的内容。

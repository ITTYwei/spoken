# 提示词总览（中文对照）

> 本文档把 spoken-server 项目里**所有会送进大模型**的提示词统一翻译成中文，并标注每段提示词的
> **触发时机**、**源码位置**与**注入角色**。源码里的提示词仍是英文，本文仅供阅读/评审，**不改变运行行为**。

整理时间：2026-10-14，以当时源码为准。

## 一、清单与触发时机

| # | 提示词 | 源码位置 | 注入角色 | 触发时机 | 频率 |
|---|--------|----------|----------|----------|------|
| 1 | 主教练系统契约（instruction） | `agent/spoken-server.yml:63` | SYSTEM | 装配期固化进 Runner，之后每轮对话都发送 | 每轮 |
| 2 | Agent 元数据（description） | `agent/spoken-server.yml:57` | 系统消息尾部（ADK 可能附加） | 同上 | 每轮 |
| 3 | 本轮系统策略 `buildSystemPolicy` | `ReplyPromptBuilder.java:60` | SYSTEM（经 `{turn_policy?}` 注入） | 教学生产链路每轮生成回复前 | 每轮 |
| 4 | 六级语言规则 `managedLanguage` | `ReplyPromptBuilder.java:104` | 嵌在 #3 内 | 同上 | 每轮 |
| 5 | 本轮用户消息 `buildManaged` | `ReplyPromptBuilder.java:30` | USER | 同上 | 每轮 |
| 6 | 上下文包装 prefix / footer | `ChatContextService.java:48` | USER | 同上 | 每轮 |
| 7 | 历史数据段 | `HistoryProvider.java:61` | USER（数据段） | 同上 | 每轮 |
| 8 | 长期记忆数据段 | `LongTermMemoryProvider.java:29` | USER（数据段） | 同上 | 每轮 |
| 9 | 场景人设 | `db/migration/V6__conversation_plan.sql:141,148` | USER（Scenario role DATA） | 建会话取场景时 | 随场景 |
| 10 | 面试阶段剧本 | `db/migration/V6__conversation_plan.sql:167,178,189` | 阶段推进（当前仅存定义） | 面试场景推进到该阶段时 | 视阶段 |
| 11 | 后台能力观察指令 | `AbilityEvidenceService.java:56` | 独立短会话 SYSTEM | 用户「独立回答」后异步触发 | 条件触发 |
| 12 | 字幕翻译指令 | `ConversationTranslationService.java:59` | 独立短会话 SYSTEM + USER | 用户点「翻译字幕」时 | 按需 |
| 13 | 开场白（脚本轨） | `V6__conversation_plan.sql:143,150` | 不经 LLM，直接 TTS | 会话开场时 | 每场一次 |

## 二、一次教学回复的提示词装配顺序

```
spoken-server.yml instruction（系统契约，末尾含 {turn_policy?} 占位符）
        │  装配期读取，固化进 LlmAgent
        ▼
ChatService.handlePreparedTextStream(preparedMessage, systemPolicy)
        │  state.put("turn_policy", systemPolicy)   ← 每轮替换占位符，只建短生命周期会话
        ▼
┌──────────────── SYSTEM ────────────────┐
│ instruction(#1) + 本轮系统策略(#3)      │
└────────────────────────────────────────┘
┌───────────────── USER ─────────────────┐
│ prefix（数据声明，#6）                  │
│ dialog:*（最近 6 组历史，#7）           │
│ older_user_excerpts（更早摘录，#7）     │
│ memory:*（最多 3 条长期记忆，#8）        │
│ footer（CURRENT TURN 标记，#6）         │
│ 本轮用户消息（#5，含场景人设 #9）        │
└────────────────────────────────────────┘
```

关键机制：ADK 的 `{turn_policy?}` 由 `ChatService.java:206` 写入短会话 state 注入 SYSTEM；
`instruction` 属装配期固化，**运行期改配置不生效，必须重跑装配链**（`RunnerNode.java:109`）。

## 三、不含在内的内容

- 项目**没有**用工具调用（function calling）保存用户画像；主教练 `spokenCoach` 未挂任何技能 / MCP 工具。
- `docs/prompt/` 下已有的 `chat-prompt.md`、`login-prompt.md`、`nginx-html-api-integration.md` 是**页面开发需求**，不是模型提示词，故未纳入。
- `agent/demo.yml`、`agent/test-agent.yml`、`agent/parallel_research_app.yml`、`agent/only-one-agent.yml` 与 `src/test/**` 下的 instruction 均为**框架脚手架示例**，生产未启用，本文不翻译。

## 四、分册

- [`01-主教练系统提示词.md`](01-主教练系统提示词.md)
- [`02-每轮系统策略与语言规则.md`](02-每轮系统策略与语言规则.md)
- [`03-每轮用户消息.md`](03-每轮用户消息.md)
- [`04-上下文包装与数据段.md`](04-上下文包装与数据段.md)
- [`05-场景人设与阶段剧本.md`](05-场景人设与阶段剧本.md)
- [`06-旁路模型调用.md`](06-旁路模型调用.md)

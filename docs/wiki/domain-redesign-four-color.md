# 口语陪练领域重构设计（四色建模）

> **这份文件回答三个问题**：现有业务真正在管哪些事实？这些事实该按什么边界切成领域？
> 为什么是这么切的（而不是别的切法）？—— 第三问是重点：**过程比结论重要**，
> 所以每一步都写出「看了什么证据 → 推出什么 → 否掉了哪些备选」。
>
> 事实来源：`spoken-server-*` 七个模块的源码（2026-10-16 重构后的 `identity / content / coaching / assessment / learning / companion` 六个领域根包）、
> Flyway `V1~V18`、以及 [`product-design.md`](./product-design.md)、[`user-data-model.md`](./user-data-model.md)、[`llm-wiki.md`](./llm-wiki.md)。
> 已有的边界说明见 [`ddd-refactoring-proposal.md`](./ddd-refactoring-proposal.md)（**上一轮**结果）；本文件是**在它之上的下一轮**，
> 明确写出哪些边界保留、哪些是错的、为什么。
>
> 建模方法：四色原型（Peter Coad《彩色 UML 建模》）。
> 方法论参考外部资料：[四色原型（百度百科）](https://baike.baidu.com/item/%E5%9B%9B%E8%89%B2%E5%8E%9F%E5%9E%8B)、
> [四色原型图进行业务建模的思考](http://www.cnblogs.com/greeny/archive/2010/09/16/1828240.html)、
> [DDD-领域模型-四色原型](https://blog.csdn.net/wuzxc520/article/details/78897135)、
> [四色建模法（PDF）](https://gitee.com/lgc653/OOAD/raw/master/pdf/%E5%9B%9B%E8%89%B2%E5%BB%BA%E6%A8%A1%E6%B3%95.pdf)。
> 本文的术语与判据在 §1 明确列出，不依赖读者已熟悉该方法。

---

## 目录

| 节 | 内容 | 这一节要回答的问题 |
| --- | --- | --- |
| §1 | 方法与判据 | 四种颜色各自的**判据**是什么？为什么判据必须是"有没有时间"？ |
| §2 | 事实采集 | 系统里到底有哪些事实、规则、阈值、外部资源？（带代码证据） |
| §3 | 领域故事 | 用一个完整故事把事实串起来 —— 谁在什么时候做了什么 |
| §4 | 着色 | 把每个概念染成一色，并写出**染色理由**与**易错点** |
| §5 | 边界诊断 | 现有六个领域哪里切错了？逐条给出证据 |
| §6 | 聚合推导 | 从"时标"与不变式推导聚合边界，并做事务/并发校验 |
| §7 | 领域蓝图 | 最终的领域根包、各色元素归属、依赖方向 |
| §8 | 代码落点 | 目录结构、端口、类迁移映射表 |
| §9 | 迁移与验证 | 分几步走、每步的验收标准、不做什么 |
| §10 | 自检清单 | 逐条对照四色判据与已有业务规则，确认没有丢规则 |
| §11 | `cases/conversation` 专项评估 | 落地校准：`media` 抽取的真正工作面在哪里 |

### 本文的证据等级（阅读时请先看这一条）

本文件混合了三种东西，正文中已尽量标注，这里给出统一声明：

| 等级 | 含义 | 出现位置 |
| --- | --- | --- |
| **已核对事实** | 逐行读过源码/迁移脚本/两个独立复核报告交叉确认 | §2 全部（含已被修正的 14 处）、§8 的"现值"列 |
| **建模判断** | 依据四色判据与不变式推出的结论，可能还有别的合理解法 | §4、§6、§7 |
| **设计主张 / 意见** | 我认为应该怎么做，**不是**代码事实 | §5 的"结论"、§7.5、§7.6、§9.2、§10 |

§2 的数值与依赖关系已由一次**对抗式复核**逐条验证，复核明确否掉了原稿 **14 处**错误，
其中 7 处会直接导致误读代码或写错实现：

- 3 张表（`user_device`、`user_login_log`、`conversation_scenario_stage`）被误判为"有代码读写"；
- `user_practice_plan` 被误判为"无代码引用"（实际被开场事实查询读）；
- 引用了一个**不存在**的常量 `WARN_SCORE`；
- `training_session.device_id` 的可空性写反（它是 `DEFAULT NULL`）；
- `closeSessionsOfUser` 尚不存在（只有 `closeSessionsOfDevice`）；
- 15 秒心跳的层写错（在 Trigger 层，不在 Case 层）；
- 首次开场（FIRST）的问号规则写反（要求**不含**问号，不是"恰好一个"）。

这些错误已在原处修正，并在 §10.5 留了对照表。**但复核未运行编译与回归测试**
（复核会话的 shell 不可用），所以"包搬移后仍能编译 / 通过 217 项回归"属于 §9 的**待验证项**，不是已证事实。

---

## §1 方法与判据

### 1.1 四种颜色

四色原型把领域中的每一个概念按**它在时间里的角色**染成四种颜色之一：

| 颜色 | 原型名（缩写） | 记号 | 一句话判据 |
| --- | --- | --- | --- |
| 绿色 | 参与方 / 地点 / 物品（PPT: Party, Place, Thing） | 类 | **它有身份，且身份会跨事件延续** —— "谁 / 在哪里 / 用什么东西" |
| 黄色 | 角色（Role） | 类 | **某个参与方在某段关系里扮演的身份** —— 同一个绿色，在不同场景下能力不同 |
| 蓝色 | 描述（Description） | 类 | **它是"类型的定义 / 目录 / 规则"** —— 描述别的对象"是什么、有哪些可能值、按什么规则算" |
| 粉色 | 时标 / 事件事实（MI: Moment-Interval，时刻-时段） | 类 | **它发生在某时，或持续某段区间，或记录"某个状态被改变了"** |

### 1.2 为什么判据是"时间"而不是"是不是名词"

最常见的错误是把四色当成"名词分类法"：看到"用户"就是绿色，看到"等级"就是蓝色。
真正的判据只有一条链：

```
它有没有一个"发生/持续"的时间坐标？
   ├─ 有  → 粉色
   └─ 无  → 它是不是在描述"别的对象可以是什么/有哪些可能值/按什么规则"？
              ├─ 是 → 蓝色
              └─ 否 → 它有独立身份吗？
                        ├─ 有 → 绿色
                        └─ 没有独立身份，只是某关系中的身份/能力集合 → 黄色
```

**必须按这个顺序问。** 先问时间，是因为粉色最容易漏：
本系统里 `会话`、`用户回合`、`助手回复`、`实际播完`、`评测`、`能力观察`、`开场介绍` 全都是粉色，
而它们恰恰是**唯一需要事务保护**的东西（见 §6）。如果先按名词分类，这些会被随手塞进"会话领域"或"评测领域"，
于是领域边界就变成了"页面/功能"的边界，而不是"一致性"的边界。

### 1.3 四色不是四层，也不是四个领域

Coad 的四色是**原型（archetype）**，不是分层，也不是限界上下文：

- 一个领域里可以同时有四种颜色（本项目的 `coaching` 里绿黄蓝粉都有）。
- 反过来，同一个颜色会横跨多个领域（`会话`、`评测记录`、`证据` 都是粉色，但属于不同领域）。
- 四色**不直接给出**领域边界。它给出两样东西：**候选聚合根**（粉色优先）和**依赖方向**（粉 → 黄 → 绿 → 蓝）。
  领域边界是在 §6 用**不变式 + 事务 + 并发**再筛一遍得到的。

上一轮重构文档 [`ddd-refactoring-proposal.md`](./ddd-refactoring-proposal.md) §四色建模 已经正确地说了
"不把四种颜色直接映射成四个领域"。本文件继承这一条，并且把筛选过程写出来。

### 1.4 建模步骤（本文实际走的五步）

```
步骤 1  采集事实      读代码/迁移/产品文档，只记"系统真正做了什么"，不评价
步骤 2  写领域故事    用一条时间线把事实串成"谁在何时做了什么"，暴露遗漏
步骤 3  着色          逐概念判定四色，每条写染色理由 + 反例（易错点）
步骤 4  推导聚合      以粉色为锚，用 4 个判据筛出聚合根与领域边界
步骤 5  校验          不变式清点 / 事务边界 / 并发热点 / 依赖方向 / 规则不丢失
```

四色的**依赖方向**在步骤 4 反复用到：

```
粉色（事实） ──参照──▶ 黄色（角色） ──由谁扮演──▶ 绿色（参与方/物品）
     │                                                    ▲
     └──────────────────描述──▶ 蓝色（定义/规则）─────────┘
```

一条可操作的推论：**如果一个"领域"里的粉色元素之间不需要共享任何不变式，那它们之间就是弱耦合，
可以分到不同领域；如果两个粉色元素必须在一个事务里同时成立，那它们必须在同一个领域（甚至同一个聚合）。**

---

## §2 事实采集：系统真正在管什么

### 2.1 一次真实对话的时间线（来自 `ConversationServiceImpl` / `ConversationCaseImpl` 的实现注释与代码）

```
App 上传 AEC3 后的裸 PCM（octet-stream）
  → WavEncoder 封 WAV
  → OpenTalking /transcribe          只识别，不说话
  → 落一条只有 userText 的回合 + 推 stt.final（带 turnId）
  → OpenPronounce 评测（参照文本 = expectedText 或识别文本）
      → 落 pronunciation_assessment + 推 assessment.done
  → 判本轮档位（识别文本 + 评测分 → 信号 → 滞后方档）
  → 拼提示词（人设 + 档位/六级 + 语言策略 + 本轮文本 + 评测结论 → ADK 生成回复）
  → 模型写完整段后，一次性 /say 下发（数字人随后开口）
  → 上游 speech.ended → assistant.done：定稿字幕、复位播放状态、回填回合 assistantText
HTTP 同步响应：{"text","turnId","assessment"}
```

这条时间线上有 **6 类不同的事实**，它们的时间性质完全不同（这是 §4 着色的原料）：

| 事实 | 时间性质 | 证据 |
| --- | --- | --- |
| 会话被建立并被占用 | 时段（QUEUED→READY→ACTIVE→CLOSING→CLOSED） | `SessionStatusEnum`、`ConversationSessionAggregate` |
| 用户说了一句话 | 时刻 + 定稿时刻 | `noteUserSpeechEnded()`、`ConversationTurnAggregate` |
| 模型生成了文本 | 时刻（生成结束 ≠ 播放结束） | `EventTypeEnum.TEXT_GENERATION_DONE` 注释："LLM 文本生成结束，不能据此认定音视频已播放完" |
| 这段文本**真的被念出来了** | 时刻（媒体侧回执） | `TTS_START`、`SpeechPlaybackVO`、`TeachingPlaybackVO`、`conversation_teaching_reply.completed` 注释"服务器媒体排空后提交，非手机播放回执" |
| 发音被评测 | 时刻（结果不可变） | `pronunciation_assessment`、`PronunciationAssessmentAggregate` |
| 独立表达被观察 | 时刻（模型给出，可被拒） | `learner_answer_evidence`、`AbilityEvidenceService` |

**第一个关键观察**：系统里"文本生成完成"和"媒体播放完成"是两个不同的事实，
且现有代码已经明确区分（`ASSISTANT_DONE` vs `TEXT_GENERATION_DONE`，`completed` 字段的注释）。
四色建模会把这俩染成两个不同的粉色 —— 这正是拆开"教练域"与"媒体域"的依据之一。

### 2.2 业务规则与阈值清单（只列代码里真实存在的）

| 规则 / 阈值 | 值 | 位置 | 归类 |
| --- | --- | --- | --- |
| 会话状态机 | QUEUED / READY / ACTIVE / CLOSING / CLOSED / ERROR，无迁移守卫，CLOSING 也算收尾 | `SessionStatusEnum`、`ConversationSessionAggregate` | 领域不变式（S1） |
| 开场白只播一次 | `markOpeningSpoken()` 幂等，第二次返回 false | `ConversationSessionAggregate` | 领域不变式（S2） |
| 首句决策冻结 | `freezeOpening()` / `completeOpeningPreparation()`；重试与跨午夜复用同一份 | 同上 + `OpeningDecisionVO` | 领域不变式（S3） |
| 回复归属固定 | `bindReplyTurn(turnId)`，完成后不读"最后一条回合"猜归属 | 同上 | 领域不变式（S4） |
| 回复完成只算一次 | `completeReplyOnce()` | 同上 | 领域不变式（S5） |
| 取消后旧事件必须被拒 | `invalidateReply()` 把 activeReplyId 换成 `cancelled:<uuid>`，`acceptsReply()` 校验 | 同上 | 领域不变式（S6） |
| 会话主体不可变 | `bindAccountId` 第二次不同值抛 `session_owner_is_immutable` | 同上 | 领域不变式（S7） |
| 会话内档位演进 | 信号 −2/−1/+1；合计 ≥1 → HIGH，≤−2 → LOW，否则 MID；下调立刻、上调攒 2 轮、一次只挪一档 | `LearnerLevelTracker` | 领域规则（L1） |
| 瞬时信号判定 | 无文本 −2；含中文 −2；英文词 1~2 → −1；≥8 → +1；评测 <60 → −1，≥85 → +1 | 同上 | 领域规则（L2） |
| 长期等级升级门槛 | 最近 30 天 10 场内，≥20 条 ANSWERED 且 confidence ≥ .85 且 eligible 的证据，至少跨 3 场；最近 3 场每场 ≥80% 支持，总体 ≥80%；一次只升一级 | `LearnerProgressService.promotion` | 领域规则（L3） |
| 手动档位优先 | `manualLevel` 非空则直接返回，不消耗历史升级证据 | `LearnerProgressService.begin` | 领域规则（L4） |
| 近期水平口径 | `recent10_30d_v1`：单场多数票 → 最近 30 天 10 场等权平均；<3 场不设长期水平；<0.5 LOW、>=1.5 HIGH | `RecentLevelPolicy` | 领域规则（L5） |
| 跟读过关线 | 80 分且无可信错词 | `GuidedReadingPolicy.PASS_SCORE` | 领域规则（R1） |
| 跟读纠音上限 | 首次示范不计数，可指导 3 次；第 3 次后仍不过则换句 | `GuidedReadingPolicy.MAX_CORRECTIONS` | 领域规则（R2） |
| 普通聊天纠音上限 | 同一句最多建议 2 次 | `TeachingPolicy.MAX_READING_RETRIES` | 领域规则（R3） |
| 错词可信度门槛 | `confidence >= 0.8`，且错词必须属于目标句 | `TeachingPolicy.decide`、`PronunciationEvidencePolicy` | 领域规则（R4） |
| 近期目标避重 | 本场最近 8 句不重复 | `TeachingStateAggregate.MAX_RECENT_TARGETS` | 领域规则（R5） |
| 生成内容校验 | 引号外教学交流 ≥8 字符；L1/L2 必须含中文；新句不得与近期目标重复；重试不得换目标 | `GuidedReadingPolicy.guard` | 领域规则（R6） |
| 上下文预算 | 默认 12000 字节（`current_turn_context_too_large` 的阈值） | 配置 + `ChatContextService` | 领域规则（C1） |
| 记忆只收明确陈述 | `confirmation = EXPLICIT`，禁止推断；有 `expires_at` | `agent_long_term_memory`、`LongTermMemoryService` | 领域规则（M1） |
| 遗忘边界 | `learner_profile.context_reset_turn`，不再读取更早原文 | `learner_profile`、`CompanionMemoryMapper` | 领域规则（M2） |
| 练习切换去重 | 成功动作指纹最多 256 项，满则拒绝；同 operation 不同指纹报 `practice_conflict`；版本不匹配报冲突 | `PracticeAggregate` | 领域不变式（P1） |
| 询问必须真播完才生效 | `invite()` 登记 → `heard(reply)` 校验 replyId 与 version；打断/新回合 `dismissInvitation()` | `PracticeAggregate` | 领域不变式（P2） |
| 学习请求原话 | 最长 2048；带引号不保存；明确结束学习则清空 | `TeachingStateAggregate.captureLearningRequest` | 领域规则（T1） |
| 能力观察格式 | outcome ∈ {ANSWERED, PARTIAL, HELP, DECLINED, UNCERTAIN}，candidate ∈ L1..L6，confidence 必须是数字且 ∈[0,1]，independent 必须是布尔 | `AbilityEvidenceService.parse` | 领域规则（A1） |
| 单 GPU 槽 | 整个会话生命周期只允许一个会话；排队时换 SDP → 409 `session_queued`（由 `ConversationServiceImpl.exchangeOffer` 抛出；"先关旧会话"这一步在 `SessionLifecycleWorkflow.create`） | `ConversationServiceImpl`、`SessionLifecycleWorkflow` + 上游约束 | 领域不变式（S8） |
| 会话归属 | 现状是"一个**设备键**只留一场会话"（`closeSessionsOfDevice`）；"一个**用户**只留一场"是 `user-data-model.md` §5.1 的**提议**，代码里尚不存在 `closeSessionsOfUser` | `ConversationServiceImpl.closeSessionsOfDevice` | 领域规则（S9，**待实现**） |

### 2.2b 容量、预算与配置类常量（第二轮补充：这些数字本身就是业务规则）

| 常量 / 阈值 | 值 | 位置 | 四色归属 |
| --- | --- | --- | --- |
| 会话上下文输入预算 | **12000** 字节（`spoken-ai.context.input-budget`），保留 `systemReserve = 2500 + estimate(systemPolicy)` | `ChatContextService` | 蓝色（能力/预算定义）→ companion |
| 单节成本估算 | **UTF-8 字节数 + 16**（刻意不用"字符数/4"，因为是中文） | `HybridReducer.estimate` | 蓝色 → companion |
| 上下文历史 | 最近 **6** 轮逐字 + 最多 **5** 条更早用户摘录，每条截断 **100** 字 | `HistoryProvider` | 蓝色 → companion |
| 教材课容量准入 | `HybridReducer.estimate(context) > ` **1400** ⇒ `lesson_too_large`（不截断） | `TextbookService.lesson` | 蓝色（内容准入）→ content，**但要共享同一个估算口径** |
| Agent 目录每本教材候选 | 最多 **3** 课（真实总量另存 `lessonCount`） | `TextbookService.agentCatalog` | 蓝色 → content |
| 记忆有效输入 | ≤ **300** 字；有效期 **+30 天（LANGUAGE）** / **+180 天（其他）**；召回上限 **3** 条；`practice=true` 时**从不新增事实**（但 `忘掉X` / `忘掉所有` 的**删除**分支在该判断**之前**执行，练习输入仍可触发删除） | `LongTermMemoryService` | 蓝色 → learning.memory |
| 会话就绪等待 | `readyWaitMs` = **15000**；轮询间隔 **400ms**（Case 层） | `IAppConfigPort` | 配置 → 会话/媒体 |
| 槽位释放沉降 | `slotReleaseSettleMs` = **800** | `IAppConfigPort` | **媒体资源约束** → media |
| 每主体会话数 | `maxSessionsPerDevice` = **1** | `IAppConfigPort` | **媒体资源约束**（单槽）→ media |
| 会话空闲超时 | `sessionIdleTimeoutMs` = **300000**（回收器未启用） | `IAppConfigPort` | **媒体资源约束** → media |
| 客户端心跳 | **15 秒**（**Trigger 层** `SessionController.events` 的 `Flux.interval`） | `SessionController` | 协议 → trigger（`EventTypeEnum` 里"用例层每 15s"的注释已过时） |
| 回复体量上限 | **32768** 字符（生成校验与净化两处） | `GuidedReadingPolicy.guard`、`TeachingReplyGuard` | 蓝色 → coaching |
| 开场文案规则 | **默认开场**：恰好 **1** 个问号且以问号结尾；**FIRST（首次见面）开场规则相反**：要求**不含任何** `?`/`？`/`…` 并以固定收尾句结束；FIRST ≤ **200** 字；素材摘要 ≤ **80** 字且不含问号；禁用词表；**8** 个中性话题白名单（9 个映射键）；**5** 个确定性变体 | `OpeningTextPolicy.valid(text)` 与 `valid(text, FIRST)`、`OpeningTopicPolicy`、`OpeningPolicy` | 蓝色 → coaching |
| 开场版本戳 | `opening_v2` / `six_level_recent30_v1` / `levelSource` ∈ {`learner_profile`,`safe_default`} | `OpeningPolicy` | 蓝色 + 粉色快照 |
| 跟读目标约束 | 恰好 **1** 个引号内英文目标，≤ **180** 字符，以 `[A-Za-z0-9]` 开头；引号外讲解 ≥ **8** 字 | `SpokenTaskResolver`、`GuidedReadingPolicy.guard` | 蓝色 → coaching |
| 分级词数带 | L1 **1–4** / L2 **3–8** / L3 **6–12** / L4 **12–20** / L5 **20–26** / L6 **26–40** 词 | `ReplyPromptBuilder.buildGuidedReadingLevelPolicy` | 蓝色 → coaching |
| 身份与令牌 | 账号 `[A-Za-z0-9_]{4,32}`；密码 **8–128**；令牌 **32 字节 / 30 天**，仅存 SHA-256；PBKDF2-HMAC-SHA256 **600000** 次（接受 100000–2000000）；16 字节盐 | `AccountService`、`PasswordHasher`、`TokenHasher` | 蓝色 → identity |
| 评测证据可信度 | 词级 confidence **≥0.8 且 ≤1**；电话音 heard ≤ **80** 字符；`expected`/`actual` ≤ **160** 字符且必须不同 | `PronunciationEvidencePolicy` | 蓝色 → coaching（解释规则） |
| 跟读尝试准入 | heard 词集 ⊆ target 且匹配 ≥ **2/3** 个 target 词，且转写不含汉字 | `ReadingAttemptPolicy` | 蓝色 → coaching |

> **第二个关键观察**（建模判断）：`12000`（输入预算）/ `2500`（系统预留）/ `1400`（课容量）/ `80`（开场素材）
> 这四个数字都在回答同一个问题 —— **"一段文本能不能装进这一轮"**，
> 也就是同一个**蓝色"文本容量"概念**，但现在分落在 `companion`（估算与预算）与 `content`（课容量准入）两个领域，
> 而且 `content.TextbookService` 为了做容量判断**直接 import 了 `companion` 的 `HybridReducer`**（见 §2.6）。
> 四色判据下这是**一个蓝色概念被两个领域各持一半**。本轮不强行合并领域
> （`content` 是纯定义域、`companion` 是读模型域，它们确实不同域 —— **这是设计判断**），
> 但要求把"文本容量口径"抽成一个**双方都依赖的描述对象**（放在 `shared`，或由 content 自带一份显式定义），
> 而不是 content 反向依赖 companion 的内部工具类。

### 2.2c 仓储层的行数上限与超时（第二轮补充：它们在基础设施里，但都是业务决策）

| 常量 | 值 | 位置 | 四色归属 |
| --- | --- | --- | --- |
| 能力证据读取上限 | ≤ **500** 行 | `LearnerProgressMapper.evidenceSince` | learning |
| 记忆召回读取上限 | ≤ **120** 行 | `CompanionMemoryMapper.memories` | learning.memory |
| 上下文历史读取上限 | ≤ **32** 轮（再反转为时间正序） | `ChatHistoryRepository.recent` | companion |
| 开场历史读取 | 最近 **10** 场、每场 ≤ **6** 个话题轮 | `OpeningHistoryRepository.read` | coaching（开场） |
| 开场历史时间窗 | **30 天**（CLOSED 会话、按 endedAt 倒序） | 同上 | coaching |
| 评测上传本地上限 | **15 MB**（超限本地先拒，`audio_too_large`） | `OpenPronounceGateway.requireAudio` | assessment（准入） |
| 后台观察并发与体量 | `Semaphore(2)`、超时 **15s**、输入 ≤ **2048** 字符 | `ExpressionObservationGateway` | assessment |
| 会话就绪/协商超时 | `readyTimeoutMs` **180000**、`offerRetryIntervalMs` **1000** | `OpenTalkingProperties` | media |
| 语音/评测超时 | `voiceTimeoutMs` **60000**、`assessTimeoutMs` **180000** | 同上 / `OpenPronounceProperties` | media / assessment |
| 文本流 WebSocket | 帧上限 **16 KiB**、整体超时 **5 分钟**、prefetch **16** | `OpenTalkingTextSocket` | media |
| 翻译超时 | **30 秒**（`.timeout(30s).block()`） | `ConversationTranslationService` | companion/case |

> **第三个关键观察**（建模判断）：上表没有一行是"技术调优参数"，本文认为它们都是**业务决策**：
> 500 决定"多久之前的表现还算数"，120 决定"最多记住多少条事实"，32 决定"模型能看到多少历史"，
> 180000 决定"用户最多等多久"。它们现在散落在 **Mapper XML、Gateway、Case、Properties** 四个地方
> （§2.2b 已给出跨层分布）。四色建模不要求把它们集中到一个类，
> 但要求它们**归属到对应的领域规则**：本文主张读取上限属于"该领域愿意承认多少证据"，而不是基础设施的缓存策略。

### 2.2d `llmOwner` 开关的两套链路（着色时必须注意的"双事实"）

`IAppConfigPort.llmOwner()` 在**至少 4 处**被读取（`ReplyWorkflow.speak`、`ReplyWorkflow.voice`、
`GuidedReadingCase.change`、`AppProperties.llmOwner()`），决定两种完全不同的链路：

| 取值 | 谁产出回复文本 | 评测能否插入 |
| --- | --- | --- |
| `AGENT`（默认） | Java / ADK（`ChatService.handlePreparedTextStream`） | **能** —— 评测插在 STT 与生成之间，这是整个迁移的意义 |
| `OPENTALKING` | 上游一条龙（`/speak_audio`） | **不能** |

**着色含义**：`llmOwner` 不是"配置"，它是**"谁扮演生成者这个角色"的切换**（黄色层面的替换）。
因此它归 `companion`（生成角色的声明方），而不是 `shared`（§5.8）。
同时它意味着：**同一段编排代码承载两条语义不同的链路**，
`ReplyWorkflow` 里的分支是"运行时策略选择"，属于 Case 层允许的编排，
但**两条链路的领域事实必须一致**（都写同一套会话/回合/评测事实），否则会切出两个领域。

### 2.3 外部资源与端口（粉色事实的"对手方"）

| 外部资源 | 领域端口 | 上游 | 归属判断 |
| --- | --- | --- | --- |
| 数字人引擎（会话、SDP、say/speak、事件） | `IAvatarDriverPort`、`IConversationEventBusPort` | OpenTalking `/say`、`/speak`、WebRTC | 媒体能力（支持子域） |
| 语音识别 | `ISpeechPort` / `IAvatarDriverPort.transcribeUploaded` | OpenTalking `/transcribe` | 媒体能力 |
| 发音评测 | `IPronunciationPort` | OpenPronounce `/pronunciation` | 评估领域 |
| 独立表达观察（模型） | `IExpressionObservationPort` | ADK / Spring AI | 评估领域 |
| 对话生成（模型） | `IAgentExecutionPort` | ADK Runner | 伙伴领域（只暴露 Reactor 文本流） |
| 音频封装 | `IAudioFormatPort` | 本地 WavEncoder | 媒体能力 |
| 形象/音色目录 | `IOpeningAvatarPort`、`IHomeMediaPort` | OpenTalking `/avatars` | 内容领域（目录）+ 身份（已保存选择） |
| 配置 | `IAppConfigPort` | Spring 配置 | shared |
| 数据库 / Redis / Mapper | 各 Repository | MyBatis | 基础设施 |

### 2.4 持久化现状（V1~V18 共 26 张表，按"是否真被读写"分三类）

> **本节已按独立复核修正**（复核发现原稿在这一节的分类两个方向都错了）。

**A. 真被读写的（10 个 Mapper XML + 1 个注解 DAO）**

| 表 | 谁在写/读 | 事实性质 |
| --- | --- | --- |
| `training_session` | `ITrainingSessionDao`、`IConversationHistoryDao`、`IOpeningFactsDao`、`IAccountDao`、`IAccountProfileDao`、`LearnerProgressMapper`、`CompanionMemoryMapper` | 会话（粉色） |
| `conversation_turn` | `IConversationTurnDao`、`IConversationHistoryDao`、`IOpeningFactsDao`、`IAccountProfileDao`、`CompanionMemoryMapper`、`ChatHistoryRepository` | 用户回合（粉色） |
| `conversation_teaching_reply` | `TeachingReplyMapper`、`IOpeningFactsDao`、`CompanionMemoryMapper` | 已播教学回复（粉色） |
| `conversation_opening_encounter` | `IOpeningFactsDao` | 开场介绍记录（粉色，跨会话） |
| `pronunciation_assessment` | `IPronunciationAssessmentDao`、`IOpeningFactsDao` | 评测记录（粉色） |
| `learner_answer_evidence` | `LearnerProgressMapper` | 能力证据（粉色） |
| `learner_profile` | `LearnerProgressMapper`、`IAccountProfileDao`、**`IOpeningFactsDao`、`CompanionMemoryMapper`** | 学习者档案（绿色 + 黄色） |
| `learner_recent_lesson` | `IRecentLessonDao`（注解 SQL） | 最近课（粉色，按主体） |
| `agent_long_term_memory` | `CompanionMemoryMapper`、`IAccountProfileDao` | 明确记忆（粉色） |
| `app_user` / `user_identity` / `user_access_token` | `IAccountDao` | 参与方 + 登录会话 |
| `user_profile`（部分列） | `IAccountDao`、`IAccountProfileDao` | 自述资料的**已启用列** |
| `conversation_scenario` | `IOpeningFactsDao.scenarios`、`DatabaseScenarioRepository` | 场景定义（蓝色） |
| `user_practice_plan` / `user_practice_plan_item` | `IOpeningFactsDao.plan`（经 `OpeningPlanRepository` → `OpeningPreparation`） | 练习计划（粉色**只读**） |

**明确"建了表但没有任何生产代码读写"的（不要把它们当事实来源）**：

`user_device`、`user_login_log`、`conversation_scenario_stage`、
`user_profile_item`、`user_learning_stat`、`user_practice_plan_day`、
`conversation_message`、`conversation_summary`、`conversation_finalize_task`、`user_learning_day`。

> 注意 `conversation_scenario_stage`（`interview` 的三阶段种子数据）只有 V14 的清空 UPDATE 和
> **一个集成测试**碰过它，生产代码不读 —— 与本文件 §4.3 / §10.4 的说明一致。

**B. 有表但**只有部分列**被用的**

- `user_profile`：只用了 `user_id`、`subtitle_enabled`、`translation_mode`、`preferred_avatar_id`、`preferred_voice`
  （`IAccountDao.insertProfile`、`IAccountProfileDao.profile/display/selection`）。
  V5 为自述画像设计的 `target_scene`、`self_level`、`daily_goal_minutes`、`native_lang`、`extra_json`
  以及 V9 加的 `preferred_tts_provider`、`practice_timezone` **没有任何 Mapper 引用**。
  > 第一个设计问题（**收窄后的准确表述**）：
  > "用户自述"**有落点**（`user_profile` 的两列 + `agent_long_term_memory`），
  > 但 **V5 那一批为画像准备的自述列从设计出来就没接上代码**；
  > 而"明确偏好 / 长期目标"实际上被写进了 `agent_long_term_memory`
  > （`IAccountProfileDao.deleteFact` / `saveFact`，`expires_at = now + 180 DAY`，`confirmation='EXPLICIT'`）。
  > 也就是说：**"自述事实"与"Agent 的长期记忆"现在共用一张表**。
  > 四色判据下它们确实是**同一类粉色事实**（用户明确陈述、有来源、会过期、可遗忘），
  > 只是当前这张表横跨了 identity 与 companion 两个领域（见 §5.5）。

**C. 有表但**完全没有代码引用**的（V5/V7/V9 的"下一版数据模型"）**

`user_profile_item`、`user_learning_stat`、`user_practice_plan_day`、`conversation_message`、
`conversation_summary`、`conversation_finalize_task`、`user_learning_day`。
（另见 A 组末尾列出的 `user_device`、`user_login_log`、`conversation_scenario_stage`。）

> 第二个设计问题：**同一件事有两套模型在竞争**。
> `user_profile_item`（推断画像，带 confidence / evidence_json / visible / status=rejected）与
> `learner_profile` + `learner_answer_evidence`（分级 + 证据）表达的是**同一批业务事实**——
> 而且 `user_profile_item` 是**唯一**为"用户可以推翻一条推断"设计的表（`status='rejected'` + `rejected_at`），
> 这正好是产品红线"画像必须可被质疑"的落点，却没有任何代码接它。
> 本轮必须先**选定一套**再谈领域边界，否则会把两套模型各自切一次领域（见 §7.6）。


### 2.5 现有领域根包与它们的真实职责

| 领域 | 现有实现 | 真实职责（读代码后的判断） |
| --- | --- | --- |
| `identity` | `AccountService`、`HomeService`、`AccountContext`、`TokenHasher`、`PasswordHasher`、`PreferenceIntentPolicy`、`IAccountRepository`、`IProfileRepository`、`IHomeMediaPort`、`IOpeningIdentityPort`、`IAccountLearningOverviewPort` | 登录/令牌/账号、自述资料与已保存伙伴选择、首页快照 |
| `content` | `TextbookService`、`IScenarioRepository`、`ITextbookRepository`、`ScenarioVO`、`TextbookVO`、`TextLessonVO`、`SpeakingPartEnum` | 教材/课文/场景定义与目录 |
| `coaching` | `ConversationSessionAggregate`、`TeachingStateAggregate`、`PracticeAggregate`、`ConversationTurnAggregate`、`PracticeService`、`ConversationHistoryService`、**9 个 `*Policy` 类 + 5 个静态策略类**（技巧：`*Policy` 全项目共 13 个，coaching 占 9 个）、`ConversationSceneEnum`、`SessionHandleVO`、`TeachingPlaybackVO` | 会话状态、回合、教学状态与策略、练习模式、开场决策、历史查询、媒体句柄 |
| `assessment` | `pronunciation/*`（`PronunciationAssessmentAggregate`、`PronunciationServiceImpl`、`IPronunciationPort`）、`expression/*`（`AbilityEvidenceService`、`IExpressionObservationPort`） | 发音评分记录 + 独立表达观察 |
| `learning` | `LearnerLevelTracker`、`LearnerLevelService`、`LearnerProgressService`、`RecentLevelPolicy`、`PracticeDayPolicy`、`AbilityDescriptionPolicy`、`EnglishLevelEnum`、`LearningEvidenceVO` | 会话内档位演进 + 跨场等级升级 + 能力描述 |
| `companion` | `ChatContextService`（+ provider/reducer）、`LongTermMemoryService`、`CompanionChatService`、`IChatService`、`IAgentExecutionPort`、`AgentProfileVO`、`TrustedInvocationKeys` | 上下文组装与裁剪、长期记忆、模型调用入口 |
| `shared` | `IAppConfigPort` | 公共配置端口 |

规模事实：`spoken-server-domain` 共 **232 个 .java = 156 个类 + 76 个 package-info**，
其中**只有 5 个聚合级对象**（`ConversationSessionAggregate`、`TeachingStateAggregate`、
`ConversationTurnAggregate`、`PracticeAggregate`、以及不可变的 `PronunciationAssessmentAggregate`）。
`identity` / `content` / `learning` / `companion` **完全没有聚合根**，它们的规则住在 Spring `@Service` 与静态 policy 类里。

### 2.6 现有跨领域依赖现状（这是边界判决最硬的证据）

四色要求"粉 → 黄 → 绿 → 蓝"单向，而现有 import 图里有 **9 组跨根包依赖**：

```
identity → coaching.practice     identity → coaching.conversation   identity → learning
content  → companion            content  → learning
coaching → learning             coaching → identity                 coaching → content
shared   → coaching
```

下表把其中 9 组压成 7 行（`coaching` 与 `identity` 各占一行），全部经独立复核逐条核对：

| 依赖 | 具体 | 为什么是问题 |
| --- | --- | --- |
| `identity` → `coaching.practice` | `PreferenceIntentPolicy` import `coaching.practice.service.PracticeIntentPolicy` | 身份领域为了复用"引用/角色扮演不算事实"的判定，依赖了**练习域**的 policy。同一条规则被两个领域各持一半 |
| `identity` → `coaching.conversation` | `HomeService` import `coaching.conversation.adapter.port.IAvatarDriverPort` | 身份领域为了校验"用户选的形象存在"，直接依赖**教练域的媒体端口** —— §5.1 的证据 |
| `identity` → `learning` | `AccountOverviewVO` import `AbilityDescriptionVO`；`IAccountLearningOverviewPort` import `LearningEvidenceVO` | 身份领域的**返回模型**里直接嵌了学习域的 VO |
| `content` → `companion` | `TextbookService` import `companion...HybridReducer` | 内容域（本该是纯定义）为了做**容量准入**，依赖了伙伴域的**裁剪工具** |
| `content` → `learning` | `ScenarioVO` import `LearnerLevelEnum`（`openingFor`） | 内容域的**定义**依赖了学习域的**分类法** |
| `coaching` → `learning` / `identity` / `content` | ≥10 处（`OpeningPolicy`、`TeachingPolicy`、`GuidedReadingPolicy`、`ReplyPromptBuilder`、`TeachingStateAggregate`、`OpeningDecisionVO`、`OpeningFactsVO`、`OpeningTemplates`、`TeachingDecisionVO`、`IOpeningHistoryRepository` 等） | 教练域是**最重**的依赖方。其中 `coaching → identity`（`OpeningSubjectVO`）与 `coaching → content`（`ScenarioVO`）是"事实域依赖更上游的定义方"，方向本身合理，但需要**收窄到值对象**，不能传仓储 |
| `shared` → `coaching` | `IAppConfigPort.llmOwner()` 返回 `coaching...LlmOwnerEnum` | **方向错了**：公共配置端口持有教练域的枚举，`shared` 反而依赖了业务域 |

**读法**：允许的依赖是"粉/黄 → 蓝/绿"（引用定义）；
**不允许**的是"蓝 → 粉"（定义反向依赖事实）与"蓝 → 蓝跨域"（两个领域各持一半同一个定义）。
按这个读法，9 组里需要处理的是：
`identity → coaching.practice`（蓝 → 蓝跨域）、`content → companion`（蓝 → 蓝跨域）、
`content → learning`（蓝 → 蓝跨域）、`shared → coaching`（纯方向错误）、
`identity → coaching.conversation.port`（蓝 → 粉反色向）；
`identity → learning`、`coaching → learning/identity/content` 属于"黄/粉引用定义"，方向可接受，
但要在迁移时**限制为只引用值对象与只读端口**。

### 2.7 运行态现状：全部在进程内（决定了哪些粉色可以不落库）

| 运行态 | 实现 | 是否落库 |
| --- | --- | --- |
| 会话索引（按 sessionId / 按设备） | `ConversationSessionRepository` 的两个 `ConcurrentHashMap`，**索引只在 `afterCommit` 发布**（防回滚后出现幽灵会话） | 会话本体落库，索引不落 |
| 事件总线 | `InProcessConversationEventBus`：`ConcurrentHashMap<sessionId, Sinks.Many>`，`multicast().directBestEffort()` | 不落；**无订阅者时事件被直接丢弃**（刻意：即时字幕不重放） |
| 媒体回复 / 播放 / 连接句柄 | `MediaRuntimeRegistry`：三个 `ConcurrentHashMap` + `Disposable.Swap`；`reserve*` 会 dispose 上一个持有者 | 不落 |
| 练习模式态 | `InMemoryPracticeStateRepository`（`ConcurrentHashMap.computeIfAbsent`）+ 聚合内运行态 | 不落（`learner_recent_lesson` 单独落库） |
| 会话内档位 | `LearnerLevelService` 里 `ConcurrentHashMap<sessionId, LearnerLevelTracker>` | 不落（**刻意**） |
| 工具可信上下文 | `AgentToolContextRegistry` 静态注册表，32 位 hex 令牌嵌入 SYSTEM 提示 | 不落 |

> **Redis 事实**：`infrastructure/redis/` 只有一个 `package-info.java`，
> 全仓没有任何 `RedisTemplate` / `StringRedisTemplate` / `@Cacheable` / Redisson 依赖（`pom.xml` 里也没有）。
> Redis 只出现在 `docs/dev-ops/docker-compose-*.yml`。**所以"共享状态"目前一律是进程内的**：
> 多实例部署会让会话、SSE 事件、练习态、媒体句柄全部错路。这是本轮设计必须尊重的**硬约束**，
> 也是 §9.2 判断"不引入新中间件"的依据（同时它意味着**这条约束一旦被打破，本节所有"不落库"的结论都要重算**）。

> **另一个与四色有关的现状**：媒体运行时其实已经被抽象成一个**Case 层出站端口**
> `cases.conversation.port.out.MediaRuntimePort`（`reserveReply/currentReply/finishReply/cancelReply/
> reservePlayback/finishPlayback/cancelPlayback/publishEvents/attachConnection/events/release`），
> 由基础设施的 `MediaRuntimeRegistry` 实现，并且 `ArchitectureBoundaryTest` 白名单**只允许**
> 基础设施引用 `cn.adinall.ai.cases.conversation.port.out.**` 这一个 Case 包。
> 这说明**上一轮重构已经在做 §6.3 方案 B 的同一件事**，只是把它放在 Case 层而不是领域层。
> 本设计的判决是把这部分**上移为 `media` 领域**（理由见 §6.3），而不是推翻它。

> **一个应当顺手清理的接口问题**：`IConversationCase.create(...)` **没有任何 Controller 调用** ——
> `SessionController.create` 调的是 `SessionCreationCase.create(...)`，
> 后者才真正进入 `SessionLifecycleWorkflow.create`（并固定 `scenarioId="free"`）。
> 也就是说接口上留着一个**看起来能用、实际不存在**的建会话入口。

---

## §3 领域故事：把事实串成一条时间线

四色建模第二步是**讲一个完整的故事**（Domain Story），因为着色只有在句子里才判得准。
下面这个故事逐句取自真实代码路径（`SessionCreationCase` → `ConversationCaseImpl` → `ConversationServiceImpl` → 回执路径）。

### 3.1 故事：一位学员的一次完整陪练

```
① 学员（Learner）用他注册的账号（Account）在一台设备（Device）上登录。
   —— 账号是绿色（有身份、跨会话延续）；"这台设备"是登录关系中的角色（黄色）。

② 首页展示他上次保存的伙伴选择（形象 + 音色）。
   —— 形象与音色是蓝色的"可选值目录"；"他选的那个"是 identity 里的自述事实（粉色）。

③ 他点了"开始对话"。后端先关掉他这个账号在本设备的上一场会话（单槽约束），
   再向数字人引擎申请一个会话。
   —— 会话是粉色（时段）。

④ 引擎回 QUEUED（GPU 槽被占），一段时间后 READY。在这段时间里，
   系统决定"开场说什么"：按他的手动档位或近期长期水平或场景难度，选定初始档位，
   从场景定义里挑出对应档位的开场白，冻结成一份"首句决策"。
   —— 场景（含分档开场白）是蓝色的"定义"；"这次会话的首句决策"是粉色（时刻）。

⑤ 数字人说出开场白。系统记录"这句话真的播完了"（用于跨会话的开场介绍记录），
   并标记本场"开场已说过"。
   —— 播报完成是粉色；开场介绍记录（首次介绍时间、上次形象）也是粉色，但生命周期跨会话。

⑥ 学员按住麦克风说了一句英语。

⑦ 系统识别出文本 → 落一条"用户回合" → 推 stt.final。

⑧ 系统把这段录音送去评测 → 得到分数、错词、韵律 → 落一条"评测记录" → 推 assessment.done。

⑨ 系统判定本轮档位信号：
   他这句话里有没有中文？英文词数几个？评测多少分？→ 得到瞬时信号 →
   按"下调立刻、上调攒 2 轮、一次只挪一档"得到本场档位。

⑩ 系统组装上下文：本场最近几轮的历史、明确的长期记忆、本轮的学习请求原话、
   上一轮实际播完的教学任务、本轮评测的可信证据。裁剪到预算内。

⑪ 系统把这批材料给模型，要求它：以一个问题收尾、不要报分、按档位给语言支持。
    模型流式吐文本。系统累积成整段。

⑫ 如果开了跟读：系统先**校验**模型生成的英文目标句（不得与最近 8 句重复、
    引号外必须有教学交流、L1/L2 必须含中文），不合法就让它重生成一次。

⑬ 系统把整段文本一次性交给引擎的脚本轨（/say），数字人开始念。

⑭ 引擎回 speech.ended。系统：定稿字幕、复位播放状态、
    把回合的 assistantText 回填、把"这条教学回复已完成"落库（带 spoken_text）。

⑮ 系统在后台**另起一次隔离的模型调用**观察他上一轮的独立回答：
    是不是独立回答？候选等级几？置信度多少？→ 落一条能力证据。
    —— 这次观察失败就丢弃，不阻塞对话。

⑯ 某场结束后，系统的收尾逻辑把本场证据汇总：更新近期档位信号、
    必要时最多提升一级、总结兴趣与薄弱点、推进学习日与计划完成次数。
    （表已就绪：conversation_summary / user_learning_stat / user_learning_day；
      **代码尚未接入**，见 §9。）

⑰ 他说"忘掉我喜欢篮球这件事"。系统删除那条记忆，
    并把"历史遗忘边界"推进到当前回合之后。
   —— 删除是一条粉色事实（状态改变），边界本身是学习者档案上的一个属性。
```

### 3.2 故事里被暴露出来的遗漏（这一步的价值）

写完故事后，有 4 处**现有实现没说清或说错**的地方，它们直接决定后面怎么切领域：

| # | 暴露的问题 | 证据 |
| --- | --- | --- |
| V1 | **⑯ 的收尾逻辑没有代码**。表齐了，但没有 worker、没有 `conversation_summary` 的 Mapper。所以"跨会话的学习者状态"目前**只有会话起始时的读路径**（`LearnerProgressService.begin` 升级 + `RecentLevelPolicy` 重建），没有写路径的完整闭环。 | §2.4 C 类表 |
| V2 | **⑮ 的能力观察与 ⑧ 的发音评测是两个不同的事实，但被放在同一个 `assessment` 包下**。一个产出"分数"（外部引擎），一个产出"候选等级"（模型），证据的可信度、失败语义、消费方都不同。 | `AbilityEvidenceService` vs `PronunciationServiceImpl` |
| V3 | **④ 的"首句决策"里同时塞了 5 类信息**：档位、来源、算法版本、样本场数、情境、变体、模板版本、文案、素材摘要、六级教学水平、形象 id/名称。它是一个**快照**而不是一个**事实**，但被当作会话聚合的一个字段。 | `OpeningDecisionVO` 13 个字段 |
| V4 | **⑰ 的"遗忘"跨了三个领域**：`agent_long_term_memory` 由 companion 读写；`learner_profile.context_reset_turn` 由 **identity 的 `IAccountProfileDao`** 与 **companion 的 `CompanionMemoryMapper`** 写入（**learning 的 Mapper 从不写它**）；而触发它的是 identity 的偏好工具。同一次业务动作写两张表、跨两个领域、没有共同事务边界描述。 | `CompanionMemoryMapper`、`IAccountProfileDao` |

---

## §4 着色：逐个概念染四色

### 4.1 绿色（参与方 / 地点 / 物品）

| 概念 | 现有落点 | 染色理由 | 易错点 |
| --- | --- | --- | --- |
| **账号 Account** | `app_user` + `AccountService` | 有独立身份，跨所有会话延续；会话结束它还在 | 不要把"登录凭证"也当绿色 —— 凭证是关系中可解绑的（§4.2） |
| **登录凭证 Identity** | `user_identity` | 可解绑、可重绑、有 `active_binding`；它是"账号 × 提供方"这条**关系** | 见 §4.2：凭据更像黄色（角色），但本项目把它当独立绿色更实用，§4.2 给出取舍 |
| **数字人形象 Avatar** | `IAvatarDriverPort`、`AvatarProfileVO`、`OpeningAvatarProperties` | 它是被反复使用的**物品**：有 id、封面、预览、在线状态；一次会话引用它，会话结束它还在 | 不要把"本次会话用的形象"和"形象目录里的形象"混为一谈（前者是会话的一个属性） |
| **语音音色 Voice** | `VoiceVO`、`spoken-ai.provider.opentalking.*` | 与形象同理，是物品；且"音色必须与 TTS 引擎配套"是物品本身的约束 | 不要建模成"会话的音色" |
| **教材 / 课文 Textbook / TextLesson** | `TextbookVO`、`TextLessonVO`、`resource/textbooks` | 有编号、有内容，被反复引用 | 见 §4.3：它同时是"物品"和"定义"，本项目的取舍是**定义优先**（因为内容只读、由资源文件/导入产生） |
| **场景场景内容 Scenario** | `conversation_scenario`、`ScenarioVO` | **有争议**，见 §4.3 | 关键取舍点 |

### 4.2 黄色（角色）

| 概念 | 现有落点 | 染色理由 |
| --- | --- | --- |
| **学习者 Learner** | `training_session.account_id` / `teachingSubject`（`user:<id>` 或 `device:<id>`）、`learner_profile.subject_key` | 同一个账号在陪练关系中扮演"学员"；能力等级、证据、档位都挂在这个角色上 |
| **伙伴 / 数字人角色 Companion** | `AgentProfileVO` + `spoken-server.yml` 的 instruction + `avatarId`/`voice` | 同一个数字人形象在陪练关系中扮演"陪练伙伴"；人格契约（零评判/必追问）是这个角色的能力集合 |
| **评测者 Assessor** | `IPronunciationPort` + `AbilityEvidenceService` 的固定 INSTRUCTION | 它不是一个"系统模块"，而是"谁在出证据"这个角色；发音评测是一个评测者，模型观察是另一个评测者 |
| **教练 Coach** | 提示词分层（`spoken-server.yml` + `ReplyPromptBuilder.buildSystemPolicy`） | 决定"这轮用什么语言、给什么任务"的规则集合；与"伙伴"是同一个绿色（数字人）的两个角色 |
| **登录设备 Device（在登录关系中）** | `user_device`、`deviceId` | 设备本身可以是绿色（物品），但"这个账号在这台设备上"是关系；当前实现两者混用（§5.1） |

**关于"伙伴"与"教练"的取舍（重要）**：

- 现有实现把人格契约放在 `spoken-server.yml` 的 instruction（配置），把每轮语言策略放在 `ReplyPromptBuilder`（domain）。
- 四色判据下，"伙伴"是黄色（角色），"人格契约 / 教学规则"是蓝色（描述角色怎么表现）。
- **不需要为"教练"单独建一个领域**：它和"伙伴"是同一个角色定义的两个切面（人设 + 规则），
  落在同一个领域里即可。真正需要分开的是 §1.2 里那条"文本生成"和"媒体播放"的界线。

### 4.3 蓝色（描述 / 定义 / 规则）

| 概念 | 现有落点 | 染色理由 | 关键取舍 |
| --- | --- | --- | --- |
| **场景定义（含分档开场白）** | `conversation_scenario`（`opening_line_low/mid/high`）、`ScenarioVO` | 描述"有哪些场景、每个场景的难度、开场白、默认音色" | **有争议**：它同时含"聊什么"（内容）与"怎么练"（模式）。本轮按**关键词判据**拆（§4.4） |
| **场景阶段** | `conversation_scenario_stage` | 定义多阶段推进路线 | 属内容定义；**当前无代码使用**（保留为设计位） |
| **教材目录 / 单元** | `TextbookCatalogVO`、`SpeakingPartEnum` | 描述"有哪些练习单元、属于哪个部分" | 归内容 |
| **六级教学等级 L1~L6** | `EnglishLevelEnum` | 描述"等级的定义与含义"；会话引用它 | **它不是学习者的属性，是能力的分类法**（Coad 原文："description knows its quantity"） |
| **三维语言档位 LOW/MID/HIGH** | `LearnerLevelEnum` | 同上，是"初始档位/会场档位"的取值域 | 与 L1~L6 是**两个不同的分类法**：一个给开场脚本，一个给教学难度。已由 `EnglishLevelEnum.openingLevel()` 映射 |
| **教学结果类型** | `TeachingOutcomeEnum`（11 项） | 描述"用户如何回应上一轮任务"的可能结果 | 归教练 |
| **教学动作** | `TeachingActionEnum` | 描述"下一步做什么" | 归教练 |
| **会话状态** | `SessionStatusEnum` | 描述会话可能处于的状态 | 归会话 |
| **事件类型** | `EventTypeEnum`（17 项 wire 值） | 描述"能出现的全部事件"，且 `wire()` 是**对外契约** | 归会话，但它是**边界描述**（协议），不是领域规则 |
| **提示词模板 / 人设 / 语言策略** | `spoken-server.yml`、`ReplyPromptBuilder`、`TeachingPolicy`、`GuidedReadingPolicy`、`OpeningPolicy`/`OpeningTemplates`/`OpeningTextPolicy`/`OpeningTopicPolicy` | 描述"按什么规则生成回复" | 归教练 |
| **发音评测的语言与阈值** | `PronunciationLanguageVO`（仅 `code`/`name`）；（原稿误写的 `WARN_SCORE` **不存在**；`WEAK_SCORE=60` / `STRONG_SCORE=85` 是 `LearnerLevelTracker` 的**私有**常量） | 描述"哪些语言可选"；阈值分两处 | 语言表归**评估域**；60/85 是**档位**阈值，归 learning（§2.2 L2） |
| **长期记忆的类型** | `memory_type`（偏好 / 学习目标 / 语言支持） | 描述"能记哪些类型的记忆" | 归学习者档案（§7.6 的取舍） |
| **可见性白名单** | `user_profile_item.visible`（V5/V9 设计） | 描述"哪些画像维度能出给 App" | 与"零评判"红线直接相关；归学习者 |
| **错误码 / 响应码** | `ResponseCodeEnum`、`ResponseCode` | 描述"可能的失败" | `shared` / `types` |

### 4.4 粉色（时标 / 事件事实）

**这是最重要的一节。** 下面每条都要问三个问题：
① 它是时刻还是时段？② 它由谁触发？③ 它必须和谁在同一个事务里？

| # | 概念 | 类型 | 现有落点 | 必须同事务的对象 | 备注 |
| --- | --- | --- | --- | --- | --- |
| M1 | **陪练会话 Session** | 时段 | `training_session` + `ConversationSessionAggregate` | 会话状态、开场标志、当前档位、待播任务、媒体句柄 | 系统里唯一真正带状态机的领域对象 |
| M2 | **用户回合 UserTurn** | 时刻 + 定稿 | `conversation_turn` | 该回合的 replyId 归属、评测关联 | 定稿（stt.final）后基本不变 |
| M3 | **助手回复 AssistantReply** | 时刻（生成结束） | `conversation_teaching_reply`（部分） | 教学结果、冻结档位、目标句、纠音次数 | 生成与播放**分开** |
| M4 | **实际播报完成 PlaybackCompleted** | 时刻 | `conversation_teaching_reply.completed` + `spoken_text` | M3 的完成标记；`PracticeAggregate.heard()` 的激活 | 与 M3 是**不同事实** |
| M5 | **发音评测记录 PronunciationAssessment** | 时刻 | `pronunciation_assessment` | 只关联用户回合；结果不可变 | 失败不阻断对话 |
| M6 | **独立表达观察 ExpressionObservation** | 时刻 | `learner_answer_evidence` | 只关联用户回合；可被拒（格式/置信度） | 与 M5 是两个评测者 |
| M7 | **学习证据 LearningEvidence** | 时刻（派生于 M6） | `learner_answer_evidence` 同表 | 升级判定（CAS） | 当前与 M6 共用一张表 |
| M8 | **档位信号 TurnLevelSignal** | 时刻 | 无表（内存 `LearnerLevelTracker`） | 会话内档位 | **刻意不落库**（会话是进程内的） |
| M9 | **开场决策 OpeningDecision** | 时刻 | `training_session` 的快照列（`V11`） | 会话创建 | 冻结后不再改变 |
| M10 | **开场相遇 OpeningEncounter** | 时段（首次/最近） | `conversation_opening_encounter` | 无（跨会话累计） | 生命周期跨会话，不能放会话聚合 |
| M11 | **记忆事实 MemoryFact** | 时刻 + 有效期 | `agent_long_term_memory` | 遗忘边界（`context_reset_turn`） | **跨领域的同事务需求**（问题 V4） |
| M12 | **遗忘 ForgetFact** | 时刻（状态改变） | `agent_long_term_memory` 删除 + `context_reset_turn` | M11 + 边界 | 见 V4 |
| M13 | **练习模式切换 PracticeSwitch** | 时刻（幂等动作） | `PracticeAggregate`（内存） | 模式/版本/成功指纹 | 会话内运行态 |
| M14 | **媒体会话 MediaSession** | 时段 | `MediaRuntimeRegistry`（内存） | 提供商句柄 + 上游状态 | 单 GPU 槽约束 |
| M15 | **收尾任务 FinalizeTask** | 时段（可重试） | `conversation_finalize_task` | 汇总 + 画像 + 统计 + 学习日 | **表已就绪，代码未接入** |
| M16 | **会话总结 SessionSummary** | 时刻 | `conversation_summary` | 与 M15 同事务 | 同上 |
| M17 | **学习日 / 计划日** | 时段（按日） | `user_learning_day`、`user_practice_plan_day` | 与 M15 同事务 | 同上 |
| M18 | **登录会话 / 令牌** | 时段 | `user_access_token` | 撤销 | 归身份 |

### 4.5 着色的四个易错点（本项目真实踩到的）

| 易错点 | 具体表现 | 正确判据 |
| --- | --- | --- |
| **把蓝色当粉色** | `ScenarioVO` 被会话聚合当作"我可以改的字段"用（`CompleteOpeningPreparation` 改的是决策，不是场景） | 定义（蓝）**不能**被一次活动反向改写。当前实现这一点是对的（`ScenarioVO` 是 record），要保住 |
| **把粉色当蓝色** | `conversation_teaching_reply` 里的 `english_level` 被当成"等级定义"读 | 它是"**这一轮冻结的档位**"，是粉色事实；等级定义在 `EnglishLevelEnum` |
| **把黄色当绿色** | `deviceId` 被当作会话归属主体（`training_session.device_id`），但它其实是"这账号在这设备"的角色 | 归属主体应该是黄色角色（`user:<id>` / `device:<id>`），也就是现在的 `teachingSubject` |
| **把"生成完成"和"播放完成"染成同一个粉** | `EventTypeEnum` 里 `ASSISTANT_DONE` 与 `TEXT_GENERATION_DONE` 并存，注释已明确区分 | 两个不同的粉色，需要两个不同的聚合/领域 |

---

## §5 边界诊断：现有六个领域哪里切错了

判据（§1.4 的推论）：**两个粉色元素如果不需要共享不变式，就不该因为"是同一个功能"被塞进同一个领域；
反过来，如果需要共享不变式，就不该被拆到两个领域。**

逐条给出证据。

> **阅读约定**：每小节里 <code>**证据**</code> 是已核对的代码事实；
> <code>**诊断**</code> 与 <code>**结论**</code> 是**建模判断/设计主张**（§1 证据等级表），
> 它们基于证据，但换一位建模者可能有不同取舍 —— 这是本文件最需要被讨论的部分。

### 5.1 `identity` 塞进了"首页组合"，并且反向依赖媒体

**证据**（已用源码逐条核对）：
- `IHomeMediaPort`（`domain/identity/adapter/port`）由 `OpenTalkingHomeMediaAdapter` 实现 —— 身份领域为了做首页音色试听，
  直接持有一个媒体端口。
- `IAccountLearningOverviewPort` 由 `AccountLearningOverviewAdapter` 实现 —— 身份领域为了做"我的"页面的能力描述，
  持有一个学习端口。
- **更硬的证据**：`HomeService` 直接 `import cn.adinall.ai.domain.coaching.conversation.adapter.port.IAvatarDriverPort`，
  用来校验"用户选的形象是否存在于实时目录"（`resolve` 的优先级是"显式 > 已保存 > 目录第一项"，`validate` 会拒绝目录外的 id）。
  也就是说：**身份领域的一个写操作（保存伙伴选择），校验规则依赖教练域的媒体端口。**
- `AccountOverviewVO`（身份的返回模型）直接嵌入 `learning.model.valobj.AbilityDescriptionVO`。
- `AccountService` 另外承担了明文规范与安全细节：账号 `[A-Za-z0-9_]{4,32}`、密码 8–128、
  令牌 32 字节 / 30 天且只存 SHA-256、未知账号也做一次假哈希（防时序侧信道）、改密撤销全部会话、
  别人的会话统一回 `session_not_found`。

**诊断**：`identity` 里的粉色事实只有"登录会话/令牌"与"已保存选择（自述）"。
"首页返回什么"是一个**读模型组合**（一个查询），不是领域规则。
把读模型组合放进领域，代价是身份领域反向依赖媒体与学习两个域。

**结论**：
1. `identity` 只保留"账号 + 凭证 + 自述资料 + 已保存选择"。
2. 首页组合移到 `cases/overview`（现有 `HomeQueryCase` 已经这么做了，但领域侧端口还没收回）。
3. **形象/音色的存在性校验不属于 identity**：identity 只负责"存下用户选了什么"，
   "这个 id 是否有效"应由 `content`（目录）或 `media`（引擎能力）给出**只读目录查询**，
   由 Case 在保存前调用一次。这样 `identity → coaching.port` 这条边可以删掉。

### 5.2 `coaching` 吞掉了六个本应独立的粉色族

**证据**（同属 `coaching`）：
`ConversationSessionAggregate`（会话时段）、`ConversationTurnAggregate`（回合时刻）、
`TeachingStateAggregate`（教学状态）、`PracticeAggregate`（练习切换）、
`ConversationHistoryService`（历史查询读模型）、`ConversationSceneEnum`（场景取值）、
`SessionHandleVO`（媒体句柄）、`TeachingPlaybackVO`（播报待提交）、
外加 20 个 policy（`GuidedReadingPolicy`、`TeachingPolicy`、`OpeningPolicy`、…）。

**诊断**：
- `SessionHandleVO` 是**媒体句柄**，它的生命周期由下游引擎决定。把它放在会话聚合里，
  等于让"会话"和"第三方会话"耦合；当前这个耦合被 `MediaRuntimeRegistry` 和 `IAvatarDriverPort` 掩盖着。
- `ConversationHistoryService` 是**纯读模型**，没有任何不变式，也没有写入路径。
  它与"会话状态机"共享的只有表，不共享规则。
- `ConversationSceneEnum` 与 `content` 的 `ScenarioVO` 表达同一件事（聊什么），出现两个定义。

**结论**：`coaching` 至少应拆成"会话与回合（粉）"+"教练规则（蓝）"，
把媒体句柄、历史读模型、场景取值分出去。

### 5.3 `assessment` 下两个子域是两种不同的评测者

**证据**：
- `pronunciation`：`IPronunciationPort`（OpenPronounce）→ `PronunciationResultVO`（分数/错词/音素/韵律）→ 落 `pronunciation_assessment`。
  失败降级为"本轮没有评测卡"。
- `expression`：`IExpressionObservationPort`（模型）→ 严格 JSON 校验 → `AbilityEvidenceVO`（outcome/candidate/confidence/independent）。
  失败降级为空，不阻塞。

**诊断**：两者共享"输入是用户回合"这一件事，其余全不同：
证据来源（引擎 vs 模型）、可信度语义（客观分 vs 概率）、消费方（提示词里的错词候选 vs 升级判定）。
把它们放同一个 `assessment` 根包是可以的（**它们确实是同一类东西：证据**），
但**必须明确这是"证据域"而不是"评测域"**，并让两条证据在领域内就区分开（不能共用一个聚合）。

**结论**：保留 `assessment`，但把它的职责写成"产出证据"，
并明确 `PronunciationAssessment`（不可变值）与 `ExpressionObservation`（可被拒）是**两个独立聚合**。

> 这一条是**保留**而不是推翻。上一轮的 `assessment` 边界方向是对的，只是内部职责没写清。

### 5.4 `learning` 把"会话内档位"和"跨场等级"放在一起，但它们是两个不同生命周期

**证据**：
- `LearnerLevelTracker`：会话内、内存、进程内、随会话生灭（注释明确写"本类不持久化"）。
- `LearnerProgressService`：跨会话、持久化、CAS 升级、`@Transactional`。

**诊断**：共享的只有"等级"这个词。一个的生命周期是**一次会话**，另一个是**一个学习者的一生**。
更关键的是：它们的**并发语义完全相反** ——
`LearnerLevelTracker` 是单会话单线程演进（滞后控制），`LearnerProgressService` 是多端并发的 CAS 竞争。

**结论**：两者都应留在 `learning`（它们共享同一个蓝色分类法与同一批证据），
但**必须是两个独立聚合**，不能合并。这正好符合 Coad 的判据：**粉色相同时要看生命周期是否相同**。

### 5.5 `companion` 把"上下文组装"、"长期记忆"、"模型调用"混在一起

**证据**：
- `ChatContextService` + `ContextProvider` + `HybridReducer`：读历史、裁剪到预算、产出片段。**纯读模型。**
- `LongTermMemoryService`：读写 `agent_long_term_memory`；而 `context_reset_turn` 在 `learner_profile`（学习者档案）。
- `CompanionChatService`：模型调用编排；`IAgentExecutionPort` 是端口。
- `TrustedInvocationKeys`：可信工具上下文 key。

**诊断**：
- **长期记忆不是"伙伴"的记忆，是"学习者"的事实**。
  证据：`memory_type` 的取值是"偏好 / 学习目标 / 语言支持"，
  `source_session` / `source_turn` 指向用户回合，
  遗忘要推进 `learner_profile.context_reset_turn`。
  全部指向"这是学员自己说过的话"，与档案（`learner_profile`）是同一个东西的两个切面。
  把它放在 companion，导致**一次遗忘要跨两个领域写两张表**（问题 V4）。
- 模型调用（`IAgentExecutionPort`）是**基础设施端口**，不是领域职责；
  放在 companion 尚可（因为是它声明端口），但 `CompanionChatService` 里的编排细节属于 Case。

**结论**：`companion` 收缩为"对话上下文组装（读模型）+ 伙伴/教练角色的提示词装配"；
长期记忆与遗忘**移到 learning**（与学习者档案共域、共事务）；
模型调用的编排下沉到 Case，companion 只留端口与提示词材料。

### 5.6 `content` 的场景定义混了两种东西

**证据**：`ScenarioVO` 同时含 `goal` / `systemPromptHint` / `level`（内容难度）与
`voice`（介质选择）与 `openingLine*`（脚本台词）。

**诊断**：这是**蓝色内部的两种分类法**混在一起：
- "聊什么、多难" = 内容难度分类法
- "用什么声音、开场说什么" = 交付/呈现分类法

它们可以共存于一个蓝色定义（因为都是"场景这个类型的属性"），
但**难度不能既在场景上又在学习者上**。现有实现是：有历史用学习者的长期水平，没历史才用场景难度
（`ConversationServiceImpl.initialLevel` → `OpeningPolicy`），这个优先级已经写对了。
真正的问题是 `ScenarioVO` 的 `level` 字段与 `EnglishLevelEnum` 的关系没有类型化（是 `String`）。

**结论**：保留 `content`，把 `ScenarioVO.level` 类型化为 `EnglishLevelEnum` 可选值，
并把"场景难度只在无历史时使用"写成 `OpeningPolicy` 的显式规则（现在分散在 case 与 service）。

### 5.7 归属主体判定：`device` vs `user` 仍是混合的

**证据**：`ConversationSessionAggregate.teachingSubject()` 在没有绑定时回退 `"device:" + deviceId`；
`learner_profile.subject_key` 的注释写"user账号或device匿名设备"；
`training_session.device_id` 是 `VARCHAR(128) DEFAULT NULL`（**可为空**，V1 起就是；不是 NOT NULL —— 原稿此处写错了，
`NOT NULL device_id` 在 `user_device` 上）；`SessionCreationCase` 把 device 拼成 `userNo():digest(deviceId)`。

**诊断**：**同一个学习者会有两个 subject_key 空间**（`user:7` 与 `device:xxx`），
长期记忆、能力证据、档案都按 subject_key 存，于是**匿名期的学习成果在登录后无法归属**
（`user-data-model.md` §7 已注意到"不自动猜测或归并"）。

**结论**：这是**产品决策**（是否允许匿名→正式归并），不是颜色问题。
四色建模在这里的作用是：把"学习者"这个黄色角色的**身份解析**集中到一个地方（`identity`），
让所有粉色事实只接受解析后的 `LearnerId`，不再各自拼 `device:` 前缀。

> 补充事实：`OpeningSubjectVO(deviceId, userId, timezone)` 已经提供了 `rotationKey()` = `device:<id>` / `user:<id>`，
> 默认时区 `Asia/Shanghai`，且**构造时强制要求 deviceId**。
> 也就是说"主体键"在 identity 里已经有唯一实现，问题只是**其他领域没有统一用它**。

### 5.8 `shared` 反向依赖了教练域，且承载了媒体资源约束

**证据**：
- `IAppConfigPort.llmOwner()` 的返回类型是 `coaching.conversation.model.valobj.LlmOwnerEnum` ——
  公共配置端口持有**教练域的枚举**。
- `IAppConfigPort` 同时暴露 `maxSessionsPerDevice`（1）、`sessionIdleTimeoutMs`（300000）、
  `slotReleaseSettleMs`（800）。这三项**不是教练规则，是媒体资源约束**（单 GPU 槽）。
- `readyWaitMs`（15000）、`corsAllowedOrigins`（`*`）属于编排与部署配置。

**诊断**：一个端口里混了三种东西——
① 协议开关（`llmOwner`，属于"谁生成回复文本"，是教练/伙伴域的描述）；
② 媒体资源约束（槽位、空闲、沉降）；
③ 编排与部署参数（等待、CORS）。

**结论**：`shared` 只保留③；①移到 `companion`（它的取值是"谁生成文本"），
②移到 `media`（它会成为 §7.2 里 `MediaSessionAggregate` 的不变式参数）。
这样 §2.6 表里"`shared → coaching`"这条反向边自然消失。

---

## §6 从时标推导聚合与领域边界

### 6.1 四个筛子

对 §4.4 的 18 个粉色元素，依次过四个筛子：

```
筛子 1（不变式）：它必须维护哪些"任何时候都不能被破坏"的断言？
                 没有不变式的 → 不是聚合根，降级为值对象/读模型。
筛子 2（事务）  ：哪个断言要求"和它一起提交"？把这些绑成一个聚合。
筛子 3（并发）  ：谁会同时改它？并发写者不同 → 分开（避免无谓锁竞争）。
筛子 4（归属）  ：谁有权写它？写入者必须能在一场内解析出可信主体。
```

### 6.2 逐个粉色元素的归属判决

| # | 粉色 | 筛子 1：不变式 | 筛子 2：同事务对象 | 筛子 3：并发写者 | 判决 |
| --- | --- | --- | --- | --- | --- |
| M1 会话 | 状态机合法、开场只一次、首句冻结、主体不可变、回复归属固定、完成只一次 | M1、M8、M9、M3(部分)、M4(部分) | 同一会话的所有请求（已靠 Case 层锁串行） | **聚合根**：`ConversationSessionAggregate` |
| M2 用户回合 | 归属正确、定稿后不变、幂等键唯一 | M2 + M5/M6 的关联 | 同一 session 的 voice 请求 | **聚合根**：`ConversationTurnAggregate`（或与回复合并，见 §6.4 取舍） |
| M3 助手回复 | 一次回复一个归属、取消后旧事件被拒 | 与 M1 的 `replyTurnId` / `activeReplyId`、与 M4 的完成标记 | 媒体回执线程 + 请求线程 | **不独立成根**：作为 M1 的运行态 + `conversation_teaching_reply` 的持久事实 |
| M4 播报完成 | 只算一次、旧 replyId 不得激活任务 | M3 的完成标记 + M13 的邀请激活 | 媒体完成回执可重复 | **与 M1/M3 同聚合**（现有 `completeReplyOnce()` + `PracticeAggregate.heard()` 已实现） |
| M5 发音评测 | 结果不可变、**提前落库**（`turnId` 允许为 null —— 评测可能先于回合完成）、只挂用户回合 | 只与 M2 的关联键 | 只插入，不更新 | **独立聚合**（`PronunciationAssessmentAggregate`，现有正确） |
| M6 表达观察 | 格式合法、可被拒、不阻塞 | 只与 M2 的关联键 | 只插入 | **独立聚合**（新增显式聚合，目前是裸 VO） |
| M7 学习证据 | 同一 turn 不重复计数、只有 ANSWERED+confidence≥.85+eligible 可升级 | 与升级判定的 CAS 同事务 | finalize worker + 后台观察 | **独立聚合**：`LearnerProfileAggregate`（等级 + 证据 + 手动档位） |
| M8 档位信号 | 下调立刻/上调攒 2/一次一档 | 与 M1 的档位 | 单会话单线程 | **M1 内部值对象**（`LearnerLevelTracker`，不独立） |
| M9 开场决策 | 冻结后不可改、重试复用 | 与 M1 创建 | 创建请求可能并发 | **M1 内部快照值对象** |
| M10 开场相遇 | 首次介绍只记一次、最近形象可更新 | 无跨表事务 | 会话结束回执 | **独立聚合**：`OpeningEncounterAggregate`（跨会话，不能进 M1） |
| M11 记忆事实 | 同 subject+key 唯一、必须 EXPLICIT、有有效期 | **与遗忘边界（M12）同事务** | 工具调用 + finalize worker | **与 M7 同领域**（学习者），独立聚合 `MemoryFactsAggregate` |
| M12 遗忘 | 边界单调递增、不读更早原文 | 与 M11 同事务 + 边界 | 同 M11 | **与 M7/M11 同领域**；边界是 `LearnerProfile` 属性 |
| M13 练习切换 | 指纹去重、版本匹配、邀请必须真播完 | 与 M1 的会话锁、与 M4 | 按钮与模型工具同时操作 | **M1 内部状态**（`PracticeAggregate`）+ 持久化快照可选 |
| M14 媒体会话 | 单槽、句柄唯一、状态可纠偏、空闲可回收 | 与 M1 的句柄**不需要同事务**（上游是外部系统） | 媒体线程 | **独立支持域 `media`**（见 §6.3） |
| M15 收尾任务 | 幂等领取、租约、token 校验、completed 不可重置 | 汇总 + 画像 + 统计 + 学习日（同一事务） | 多 worker 竞争 | **独立聚合**：`FinalizeTaskAggregate`（跨领域编排者：Case） |
| M16 会话总结 | 一会话一份 | 与 M15 同事务 | 同上 | **M15 事务内的结果**，独立聚合 `SessionSummaryAggregate` |
| M17 学习日 | 同日去重、streak 可重算 | 与 M15 同事务 | 同上 | **M7 领域内的按日事实** |
| M18 令牌 | 可撤销、持久 | 与登录会话 | 多端 | **`identity` 领域** |

### 6.3 关键判决：会话聚合到底该不该持有媒体句柄？

**方案 A（现状）**：`ConversationSessionAggregate` 持有 `providerSessionId` + `SessionHandleVO` + 媒体状态。
- 好处：一次会话只有一个对象，状态一致。
- 代价：会话聚合必须理解"上游会话"的生命周期；`MediaRuntimeRegistry` 与聚合各存一份状态，
  已有两处需要同步（`markStatus` 的纠偏路径）。

**方案 B（推荐）**：会话聚合只持有 `MediaLease` 值对象（`providerSessionId` + `expiresAt` + `avatarId` + `voice`），
媒体运行时状态（QUEUED/READY/ACTIVE/空闲/回收）归独立的 `media` 支持域。
- 好处：
  1. **单 GPU 槽是一个独立的资源不变式**（M14），它有自己的排队语义，不该借用会话状态机表达。
  2. **（这是解释，不是已证实的事实）** `SessionStatusEnum` 的 QUEUED/READY/ACTIVE 三个值
     在代码里唯一的来源是 `markStatus(上游纠偏)` 与 `markReady()`，即它们记录的是**上游资源状态**；
     会话自身的不变式（开场只一次、首句冻结、主体不可变、回复归属）根本不读这三个值。
     支持这一解释的直接证据：`markStatus` 的注释写"状态由上游纠偏（poll status 或收到 session.ready）"，
     且 `ConversationSessionAggregate` 的类注释明确写"**没有任何迁移守卫**"。
     会话真正关心的只有 `OPEN / CLOSING / CLOSED / ERROR`。
     §6.2 的筛子 1 一过就清楚了：会话的不变式**完全不依赖媒体处于 QUEUED 还是 READY**。
  3. 解除 §5.2 指出的"会话与第三方会话耦合"。
- 代价：一次"能不能换 SDP"的判断要跨两个对象（会话是否 OPEN × 媒体租约是否就绪）。
  现有 `canExchangeOffer()` 已经是这个复合判断，只是写法上取了个巧（`!isQueued() && !isFinished()`）。
- **判决：方案 B。** 并保留现有 `SessionStatusEnum` 的取值作为对外兼容码（`wire` 契约不能变）。

**这是本轮设计最重要的一个判决**，因为它决定了要不要新增第七个领域。

### 6.4 取舍：用户回合与助手回复是否合并成一个聚合？

**方案 A**：`ConversationTurnAggregate`（用户回合）+ 助手回复放在会话聚合的运行态。
**方案 B**：一个 `TurnAggregate` 同时装用户文本与助手回复。  

**判据**：M2 与 M3 的不变式是"回复必须归属到确定的回合"（S4）——
这条断言**跨 M2 与 M3**。筛子 2 要求它们同事务。
但现有实现已经把这条断言放在会话聚合里（`bindReplyTurn` + `replyTurnBound`），
说明**会话聚合承担了它**，于是 M2/M3 不必合并。

**判决：保留方案 A**（与现状一致）。但要把"归属"这条断言在领域里写得比现在更显式：
现在 `replyTurnId` 是一个可被 `beginReply` 重置的字段，建议改成不可变的值对象 `ReplyBinding(replyId, turnId, generation)`。

### 6.5 领域边界最终判决表

| 领域 | 时标（粉） | 角色（黄） | 参与方/物品（绿） | 描述（蓝） | 判据 |
| --- | --- | --- | --- | --- | --- |
| **identity** 身份 | 登录会话、令牌、已保存伙伴选择 | 登录身份 | 账号、设备 | 凭证类型、可见偏好键 | 只有它能判定"你是谁" |
| **content** 内容 | （无）**纯蓝绿** | — | 教材、课文、场景内容 | 场景定义、分档开场白、单元目录、场景难度 | 定义域不含任何"发生的事"，因此没有事务 |
| **coaching** 陪练 | 会话、用户回合、助手回复、播报完成、开场决策、练习切换 | 学习者、伙伴/教练 | 数字人形象、音色 | 教学规则、结果/动作枚举、提示词策略、开场策略 | 共享"这场会话内必须一起成立"的不变式 |
| **assessment** 证据 | 发音评测、表达观察 | 评测者（引擎 / 模型） | — | 语言表、可信度规则、证据格式 | 只产出证据，不解释证据 |
| **learning** 学习者 | 学习证据、档位演进、记忆事实、遗忘、学习日 | 学习者 | 学习者档案 | 等级分类法、升级门槛、可见性白名单、记忆类型 | 共享"这个学习者的能力与事实"的单调不变式 |
| **companion** 伙伴 | （无持久化粉色） | 伙伴、教练 | — | 上下文材料、提示词分层规则 | **收缩为读模型 + 提示词装配** |
| **media** 媒体（新增） | 媒体会话、租约、播放回执 | — | 数字人形象、音色 | 引擎能力、事件协议 | 独立的资源不变式（单槽） |
| **shared** | — | — | — | 配置端口 | 技术公共 |

**为什么是 7 个（+shared）而不是 6 个**：因为 §6.3 的判决。
**为什么不是 8 个**：因为长期记忆不独立成域（§5.5），它是"学习者的事实"。

### 6.6 依赖方向（四色给的箭头，加上分层约束）

```
                      coaching（粉：会话/回合/教学）
                        │              │
        ┌───────────────┼──────────────┼───────────────┐
        ▼               ▼              ▼               ▼
   content(蓝绿)   learning(黄绿)  assessment(粉)   media(粉)
   只被引用         会话起始读/     只产证据         只被 coaching
   不反向依赖       结束写          不反向依赖        引用句柄
        ▲               ▲
        └───────┬───────┘
                │
           identity(黄绿：谁在练)
                │
                ▼
           companion(读模型 + 提示词)
```

规则：
1. **蓝色不被粉色反向改写**：`content` 的定义不能因为一次会话而改变。
2. **粉色之间不互相写**：`assessment` 只产证据，`learning` 只消费证据（通过 Case 编排）。
3. **`companion` 只读**：它的输入是 coaching/learning/identity 的只读查询结果，不写任何粉色。
4. **`media` 不被学习/证据域引用**：只有 coaching 通过端口使用它。
5. **`identity` 不依赖媒体**（§5.1 的修正）：首页的音色试听移到 Case，走 `media` 端口。

---

## §7 领域蓝图

### 7.1 目录结构

保留现有模块划分（`domain / case / infrastructure / trigger / api / types / app`）与 `cases` 包名。

```text
spoken-server-domain/
└── cn/adinall/ai/domain/
    ├── identity/                     # 绿+黄：谁在练
    │   ├── model/{aggregate,entity,valobj}
    │   ├── adapter/{port,repository}
    │   └── service/
    ├── content/                      # 蓝+绿：练什么（纯定义）
    │   ├── model/valobj/{ScenarioVO, TextbookVO, TextLessonVO, ...}
    │   ├── adapter/repository
    │   └── service/{TextbookService, ScenarioService}
    ├── coaching/                     # 粉为主：这一场怎么练
    │   ├── session/                  #   会话与回合（聚合根在这里）
    │   │   ├── model/aggregate/{ConversationSessionAggregate, ConversationTurnAggregate}
    │   │   ├── model/valobj/{SessionStatusEnum, ReplyBinding, MediaLease, OpeningDecisionVO, ...}
    │   │   ├── adapter/{port,repository}
    │   │   └── service/
    │   ├── teaching/                 #   教练规则（蓝）
    │   │   └── service/{TeachingPolicy, GuidedReadingPolicy, SpokenTaskResolver, ReplyPromptBuilder, ...}
    │   ├── practice/                 #   练习模式与切换
    │   │   ├── model/{aggregate/TeachingStateAggregate, aggregate/PracticeAggregate, valobj}
    │   │   └── adapter/repository
    │   └── history/                  #   历史读模型（无不变式）
    │       ├── model/valobj
    │       ├── adapter/repository
    │       └── service/ConversationHistoryService
    ├── assessment/                   # 粉：证据
    │   ├── pronunciation/{model,adapter,service}
    │   └── expression/{model,adapter,service}
    ├── learning/                     # 黄+绿+粉：这个人的能力与事实
    │   ├── level/                    #   档位与等级
    │   │   ├── model/{aggregate/LearnerProfileAggregate, valobj}
    │   │   ├── adapter/repository
    │   │   └── service/{LearnerLevelTracker, LearnerProgressService, RecentLevelPolicy, AbilityDescriptionPolicy}
    │   ├── memory/                   #   明确记忆与遗忘边界（从 companion 迁入）
    │   │   ├── model/{aggregate/MemoryFactsAggregate, entity/LongTermMemoryEntity, valobj}
    │   │   ├── adapter/repository
    │   │   └── service/LongTermMemoryService
    │   └── progress/                 #   按日事实与升级收尾
    │       ├── model/{aggregate/FinalizeTaskAggregate, aggregate/SessionSummaryAggregate, valobj}
    │       ├── adapter/repository
    │       └── service/
    ├── companion/                    # 读模型 + 提示词材料（无粉色）
    │   ├── model/valobj/{DialogMessageVO, ContextSectionVO, AgentProfileVO}
    │   ├── adapter/{port/IAgentExecutionPort, repository/IChatHistoryRepository}
    │   └── service/context/{ChatContextService, provider, reducer}
    └── media/                        # 新增：媒体资源与投递
        ├── model/{aggregate/MediaSessionAggregate, valobj/{EventTypeEnum, MediaStateEnum, DriverCapabilityVO}}
        ├── adapter/port/{IAvatarDriverPort, ISpeechPort, IAudioFormatPort, IConversationEventBusPort}
        └── service/
```

### 7.2 各领域的核心聚合与不变式

| 领域 | 聚合根 | 必须维护的不变式（编号沿用 §2.2） |
| --- | --- | --- |
| identity | `AccountAggregate` | 凭证唯一绑定、令牌可撤销、主体不可变 |
| content | 无聚合根 | 定义只读、编号唯一 |
| coaching | `ConversationSessionAggregate` | S1（状态机）S2（开场一次）S3（首句冻结）S4（归属固定）S5（完成一次）S6（取消后拒收）S7（主体不可变）P1（去重）P2（邀请真播完） |
| coaching | `ConversationTurnAggregate` | 回合定稿后不可变、幂等键唯一、评测关联正确 |
| assessment | `PronunciationAssessmentAggregate` | 结果不可变、只挂用户回合 |
| assessment | `ExpressionObservationAggregate`（新增） | 格式合法、可被拒、不阻塞 |
| learning | `LearnerProfileAggregate` | L1（会话内滞后）L3/L4（升级门槛与手动优先）M2（遗忘边界单调） |
| learning | `MemoryFactsAggregate` | M1（只收明确陈述）M2（与边界同事务）同 subject+key 唯一 |
| learning | `FinalizeTaskAggregate` | 幂等领取、租约、completed 不可重置、一事务内完成全部汇总 |
| media | `MediaSessionAggregate` | S8（单槽）、句柄唯一、状态可由上游纠偏、空闲可回收 |
| companion | 无聚合根（只读） | 无 |

### 7.3 用例（Case）归属

| Case | 跨域 | 说明 |
| --- | --- | --- |
| `SessionCreationCase` | coaching → media, identity, content, learning | 关旧会话、解析主体、读场景与档位、建会话、冻结首句 |
| `ConversationCaseImpl` / `ReplyWorkflow` | coaching → assessment, learning, companion, media | 一轮对话总装：识别→取证→判档→拼上下文→生成→校验→下发 |
| `PracticePlaybackCase` | coaching → media | 播报完成回执与练习邀请激活（现已解循环依赖） |
| `GuidedReadingCase` | coaching → assessment | 跟读目标生成校验与重生成 |
| `AgentActionCase` | coaching, identity, learning | 模型工具入口（选课、换伙伴、偏好、遗忘） |
| `HomeQueryCase` | identity, content, learning, media | 首页读模型组合（**从 identity 迁出**） |
| `ConversationTranslationService` | coaching → companion | 按需翻译 |
| `FinalizeCase`（新增） | learning → coaching, assessment | 会话收尾：总结、画像、统计、按日事实（**对应 M15~M17，代码未接入**） |

### 7.4 端口归属

| 端口 | 归领域 | 实现归基础设施 |
| --- | --- | --- |
| `IAvatarDriverPort` | media | `OpenTalkingAvatarDriver` |
| `ISpeechPort` / `IAudioFormatPort` | media | `WavAudioFormatAdapter` |
| `IConversationEventBusPort` | media | `InProcessConversationEventBus` |
| `IPronunciationPort` | assessment | `OpenPronouncePronunciationAdapter` |
| `IExpressionObservationPort` | assessment | `ExpressionObservationAdapter` |
| `IAgentExecutionPort` | companion | `AgentExecutionAdapter` |
| `IOpeningAvatarPort` | content（形象目录） | `ConfiguredOpeningAvatarAdapter` |
| `IAppConfigPort` | shared | — |
| `IHomeMediaPort`（**删除**） | —— | 首页试听改走 `media` 端口（§5.1） |
| `IOpeningIdentityPort`（**删除**） | —— | 主体解析由 Case + `identity` 服务负责（§5.7） |
| `IAccountLearningOverviewPort`（**收窄**） | identity 只保留"读取本人档案摘要" | `AccountLearningOverviewAdapter` |

### 7.5 基础设施包结构（同时修正现有违规）

现有 `infrastructure` 用的是**技术分层 + 领域子包**（`identity/persistence/mapper`、
`platform/media`、`integration/opentalking`），这与 `.skill-sources/xfg-ddd-skills` 规定的
唯一合法结构（`adapter/port`、`adapter/repository`、`dao/`、`redis/`、`gateway/`、`config/`）不一致。

建议目标结构（可分批迁移）：

```text
cn/adinall/ai/infrastructure/
├── adapter/
│   ├── port/          # 实现 domain/*/adapter/port（只调 gateway/）
│   └── repository/    # 实现 domain/*/adapter/repository（只调 dao/ + redis/）
├── dao/               # MyBatis DAO 接口
│   └── po/            # PO
├── gateway/           # HTTP/WS/RPC 客户端
│   ├── opentalking/
│   ├── openpronounce/
│   ├── agent/
│   └── dto/
├── redis/
└── config/
```

**迁移顺序建议**：先落 `dao/` + `dao/po/`（机械搬移，风险最低），
再落 `adapter/repository/` 与 `adapter/port/`（此时旧包名与新包名并存，靠 `ArchitectureBoundaryTest` 守住不新增反向依赖），
最后落 `gateway/`。**不要试图一次搬完**：现有 `identity/persistence/mapper` 这类包名一旦动，
Mapper 的 `namespace`、`@MapperScan`、XML 路径要同时改，一次性改动面太大、
而 `V1~V18` 与在线回归都无法覆盖它。

> 现状里也有**做对了的部分**要保留：`ArchitectureBoundaryTest` 已经用源码扫描守住依赖方向，
> 并且基础设施对 Case 的引用被收窄到**只允许** `cn.adinall.ai.cases.conversation.port.out.**`
> （见 `ArchitectureBoundaryTest` 第 39–43 行）。这条规则是本设计"媒体端口"判断的技术支撑，
> 迁移后应改为"基础设施只允许实现 `domain/**/adapter/**` 与 `media` 的端口"。

### 7.6 必须一起做的三个"二选一"（否则领域会切两遍）

| # | 二选一 | 建议 | 理由 |
| --- | --- | --- | --- |
| 1 | 画像用 `user_profile_item`（V5/V9）还是 `learner_profile` + `learner_answer_evidence`（V12） | **选 V12 一套**，把 V5/V9 的推断画像列降级为"未启用实验" | 代码已经在用 V12；两套并存会让"学习者档案"这个绿色出现两个身份 |
| 2 | 自述事实（偏好/长期目标）放 `user_profile` 还是 `agent_long_term_memory` | **自述事实归 `identity`**；`agent_long_term_memory` 只保留"有来源、会过期、可遗忘"的**记忆事实**，并把它归 `learning` | §5.5：现在 `managePreference` 把长期目标写进了"Agent 记忆"，语义错位，且遗忘要跨域写两张表 |
| 3 | 会话表名 `training_session` 改不改 | **随本轮一起改为 `conversation_session`**（同步 DAO/PO/Mapper） | 不改名会让"会话"这个粉色在代码里继续叫 training，`user-data-model.md` §8.5 已把它列为待办；改名必须与代码同批 |

---

## §8 现有类 → 新归属映射表

### 8.1 领域层映射

> 下表已按独立复核修正三处**现值**错误（`IOpeningIdentityPort`、`LongTermMemoryEntity`、`IChatService`）；
> 新值列一律省略 `model/` 段，与 §7.1 的目录结构对应。

| 现有类 | 现值 | 新值 | 变更 |
| --- | --- | --- | --- |
下表中 `SessionStatusEnum`、`SessionHandleVO`、`TeachingPlaybackVO`、`OpeningDecisionVO` 的"新值"一律省略 `model/` 段。

| `ConversationSessionAggregate` | coaching.conversation | coaching.session.aggregate | **移动**；媒体句柄改为 `MediaLease` 值对象 |
| `ConversationTurnAggregate` | coaching.conversation | coaching.session.aggregate | 移动 |
| `TeachingStateAggregate` | coaching.conversation | coaching.practice.aggregate | 移动（它是练习态不是会话态） |
| `PracticeAggregate` | coaching.practice | coaching.practice.aggregate | 移动（包名规范化） |
| `SessionStatusEnum` | coaching.conversation | coaching.session.valobj | 移动；**新增** `MediaStateEnum` 承接 QUEUED/READY/ACTIVE |
| `EventTypeEnum` | coaching.conversation | media.valobj | **移动**（事件协议是媒体边界） |
| `SessionHandleVO` / `TeachingPlaybackVO` | coaching.conversation | coaching.session.valobj | 移动 |
| `OpeningDecisionVO` | coaching.conversation | coaching.session.valobj | 移动；字段收敛为"决策快照" |
| `OpeningPolicy` / `OpeningTemplates` / `OpeningTextPolicy` / `OpeningTopicPolicy` | coaching.conversation | coaching.teaching | 移动 |
| `TeachingPolicy` / `GuidedReadingPolicy` / `SpokenTaskResolver` / `ReplyPromptBuilder` / `TeachingReplyGuard` / `ReadingAttemptPolicy` / `PronunciationEvidencePolicy` / `EnglishLearningQuestionPolicy` | coaching.conversation | coaching.teaching | 移动 |
| `ConversationHistoryService` + history VO | coaching.history | coaching.history | 不变（但明确标注"读模型"） |
| `PronunciationAssessmentAggregate` / `PronunciationServiceImpl` / VO | assessment.pronunciation | assessment.pronunciation | 不变 |
| `AbilityEvidenceService` / `AbilityEvidenceVO` | assessment.expression | assessment.expression | 不变；**新增**显式聚合 |
| `LearnerLevelTracker` | learning.service.level | learning.level.service | 移动（包名规范化） |
| `LearnerProgressService` / `RecentLevelPolicy` / `LearnerLevelService` / `AbilityDescriptionPolicy` / `PracticeDayPolicy` | learning.service.level | learning.level.service | 移动 |
| `LongTermMemoryService`（**`companion.service.memory`**）/ `LongTermMemoryEntity`（**`companion.model.entity`**） | companion | learning.memory | **迁移领域**（§5.5） |
| `ChatContextService` + provider/reducer | companion.service.context | companion.service.context | 不变；只读化 |
| `CompanionChatService`（`companion.service.chat`）/ `IChatService`（**`companion.service`**） | companion.service | 拆：提示词装配留 companion，编排下沉 Case | 重构 |
| `AgentInvocationVO` / `TrustedInvocationKeys` | companion.model | companion.model | 不变 |
| `AccountService` / `HomeService` | identity.service.impl | identity.service.impl / **`HomeService` 拆为 `AccountProfileService`** | 重构（删掉媒体与学习端口） |
| `PreferenceIntentPolicy` / `PasswordHasher` / `TokenHasher` / `AccountContext` | identity | identity | 不变 |
| `ScenarioVO` / `TextbookVO` / `TextLessonVO` / `TextbookCatalogVO` / `SpeakingPartEnum` | content | content | 不变；`ScenarioVO.level` 类型化 |
| `ConversationSceneEnum` | coaching.conversation | **删除** | 与 `content` 的场景定义重复（§5.2） |
| `IHomeMediaPort` / `IOpeningIdentityPort` | identity（**两个端口都在 `identity.adapter.port`**） | **删除** | §5.1 / §5.7 |
| `IAccountLearningOverviewPort` | identity.adapter.port | **收窄**：只保留"读本人档案摘要"，`LearningEvidenceVO` 不再出现在 identity 的端口签名里 | §2.6 |
| `IAppConfigPort` | shared | **拆分三份**：`llmOwner` → companion；`maxSessionsPerDevice` / `sessionIdleTimeoutMs` / `slotReleaseSettleMs` → media；`readyWaitMs` / `corsAllowedOrigins` 留 shared | §5.8 |
| `OpeningSubjectVO` + `rotationKey()` | identity.valobj | **提升为主体键的唯一入口**，其他域不得再拼 `device:` 前缀 | §5.7 |
| `HybridReducer.estimate` 的容量口径 | companion（内部工具） | **抽出为显式描述**供 content 使用，消除 `content → companion` | §2.2b / §2.6 |

### 8.2 新增类

| 新类 | 领域 | 职责 |
| --- | --- | --- |
| `MediaSessionAggregate` | media | 单槽资源、句柄、上游状态纠偏、空闲回收 |
| `MediaLease` | coaching.session.valobj | 会话持有的媒体租约（不可变） |
| `MediaStateEnum` | media.valobj | QUEUED/READY/ACTIVE/CLOSING/CLOSED/ERROR（与对外 wire 兼容） |
| `ReplyBinding` | coaching.session.valobj | `(replyId, turnId, generation)` 不可变归属 |
| `ExpressionObservationAggregate` | assessment.expression | 观察的合法性校验与状态 |
| `LearnerProfileAggregate` | learning.level | 稳定等级 + 会话内档位 + 手动档位 + 遗忘边界 |
| `MemoryFactsAggregate` | learning.memory | 明确事实的确认/更正/过期/遗忘 |
| `FinalizeTaskAggregate` | learning.progress | 收尾任务的幂等与租约 |
| `SessionSummaryAggregate` | learning.progress | 会话总结 |
| `ScenarioService` | content | 场景定义的查询与难度解析（从 `ScenarioVO` 内联逻辑提出） |
| `TenantCapacityPolicy`（或同等命名的描述对象） | shared 或 content | 统一"文本容量"口径（`estimate` 算法 + 12000 预算 + 1400 课容量 + 2500 系统预留），供 content 与 companion 共用而不互相 import |

### 8.3 移动位置但职责不变的"批量"清单（避免漏搬）

| 类别 | 现有 | 新 |
| --- | --- | --- |
| 命令实体 | `coaching.conversation.model.entity.{CreateSessionCommandEntity, SayCommandEntity, SpeakCommandEntity, StartConversationCommandEntity}` | 分别归 `coaching.session.entity`（建会话 / 说 / 念）与 `media.entity`（下发音视频） |
| 媒体值对象 | `coaching.conversation.model.valobj.{DriverCapabilityVO, DriverModelVO, IceServerVO, VoiceOptionsVO, TranscribeOptionsVO, TranscriptChunkVO}` | `media.valobj` |
| 会话值对象 | `coaching.conversation.model.valobj.{TeachingTaskVO, TeachingDecisionVO, TeachingPlaybackVO, ReadingModeVO, PronunciationFeedbackVO, UserUtteranceVO, ConversationEventVO, OpeningFactsVO, OpeningEncounterVO, OpeningMaterialVO, SessionHandleVO}` | `coaching.session.valobj`（`OpeningEncounterVO` 归 `coaching.session.valobj` 但由独立仓储持有） |
| 编排模型 | `case.conversation.{command,result,model,impl,workflow,runtime,translation,port}` | 不动（Case 层已经是正确归属） |
| 上下文 provider / reducer | `companion.service.context.{provider.impl.HistoryProvider, provider.impl.LongTermMemoryProvider, reducer.HybridReducer}` | 不动；`LongTermMemoryProvider` 改为**只读**查询 learning 的记忆（依赖方向由 Case 保证） |
| 仓储接口 | 各域 `adapter.repository.*` | 不动 |

---

## §9 迁移路线与验收

### 9.1 分步（每步独立可验证，不夹带）

| 步骤 | 内容 | 写范围 | 验收 |
| --- | --- | --- | --- |
| 0 | **本设计评审**（本文件） | `docs/wiki/domain-redesign-four-color.md` | 三条"二选一"（§7.6）拍板 |
| 1 | 纯搬移：`coaching` 内部拆 `session / teaching / practice / history` 子包；不改行为 | `spoken-server-domain/coaching/**`、`case/**` 的 import | 编译通过 + 217 项回归不变 + `ArchitectureBoundaryTest` 通过 |
| 2 | `companion.memory` → `learning.memory`；`EventTypeEnum` → `media.valobj`；删 `ConversationSceneEnum` | 同上 + `infrastructure` | 同上；遗忘路径的两张表写入放进同一事务（或显式说明跨域一致性依据） |
| 3a | **先做零风险清理**：修 `IConversationService.refreshStatus` 等注释与实现的一致性；处置死接口 `IConversationCase.create(...)`；`@Autowired(required=false)` 改构造器注入 | `cases/conversation/runtime`、`trigger/http/SessionController` | 行为不变 + 回归不变；**必须先做**，否则 3b 的 diff 无法定位回归来源（见 §11.5） |
| 3b | 抽出 `media` 领域：`MediaSessionAggregate` + 迁移 `IAvatarDriverPort` / `ISpeechPort` / 事件总线端口；会话改用 `MediaLease`；**并把 `ConversationServiceImpl.applyDriverEvent` 的分支按领域切开**（§11.3 #1） | `domain/media/**`、`cases/conversation/runtime`、`infrastructure/integration/opentalking`、`adapter/port` | 会话不再访问上游句柄内部；`canExchangeOffer` 改为"会话 OPEN × 租约就绪"；端到端真机联调 |
| 3c | 收敛"猜最后一条回合"（§11.3 #2）：绑不到归属就拒绝归档，不再 `filter(userText != null && assistantText == null)`；序号改用 `next_message_seq` 递增（§11.3 #3） | `cases/conversation/runtime`、`domain/coaching/session`、新迁移 | 竞态用例（回复期间用户发下一句）有专测；`V1~V18` 不变 |
| 4 | `identity` 减负：删 `IHomeMediaPort` / `IOpeningIdentityPort`，首页试听走 `media` 端口，`HomeQueryCase` 承接组合 | `domain/identity`、`cases/overview`、`trigger/http` | `ArchitectureBoundaryTest` 增加"identity 不得依赖 media/learning"一条 |
| 5 | 聚合显式化：`ReplyBinding`、`ExpressionObservationAggregate`、`LearnerProfileAggregate`、`MemoryFactsAggregate` | `domain/coaching`、`domain/assessment`、`domain/learning` | 每条 §7.2 的不变式有一个单测 |
| 6 | 持久模型收敛：选定 V12 一套；自述事实归 `identity`；`training_session` → `conversation_session` | 新迁移 `V19+`、`dao/po`、mapper XML | 迁移脚本在独立 MySQL 验证；`V1~V18` 字节不变 |
| 7 | 接入收尾（M15~M17）：`FinalizeCase` + worker | `domain/learning/progress`、`cases`、`infrastructure` | 幂等与租约测试；重复事件不重复累计 |
| 8 | 基础设施包结构对齐六边形规范（§7.5） | `spoken-server-infrastructure/**` | `ArchitectureBoundaryTest` 扩展为"infrastructure 只允许 adapter/dao/gateway/redis/config" |

### 9.2 不做什么（明确划界）

- **不改 HTTP 契约**：`EventTypeEnum.wire()`、`/api/v1/**` 路径与 JSON 形状不变（Android 依赖）。
- **不改档位算法与阈值**：§2.2 的 L1~L5、R1~R6 全部是产品决策，本次只搬位置不改数值。
- **不引入新中间件**：不用 MQ、不用事件溯源。**本设计的判断是**"内存运行态 + 数据库事实"这条边界应当保留
  （依据：会话是进程内的，重启即丢 —— `ConversationSessionAggregate` 的注释已经论证过；
  但注意这是设计判断，且**多实例部署会破坏它**，因为事件总线、媒体注册表、练习态、会话索引全在进程内，见 §2.6 与运维事实）。
- **不做多用户并发**：单 GPU 槽是硬约束，媒体领域只是把它显式建模，不解决它。

### 9.3 验证方式（沿用项目已有手段）

1. `ArchitectureBoundaryTest`（扩展依赖方向断言）+ MyBatis Mapper 全量解析。
2. 全量 `mvn -DskipTests=false` 回归（本项目需 `forkMode=once` + Mockito java agent，见 `ddd-refactoring-proposal.md` §验证与维护）。
3. 关键不变式的定向单测：S1~S9、P1~P2、L1~L5、R1~R6、M1~M2。
4. 真机端到端：建会话 → 开场 → 一轮语音 → 跟读 → 打断 → 关会话 → 历史查询。
5. 迁移脚本在**独立 MySQL** 验证，不碰已部署库；`V1~V18` 保持字节不变（本项目铁律）。

---

## §10 自检清单

### 10.1 四色判据自检

| 检查项 | 结果 |
| --- | --- |
| 每个概念都用"时间 → 描述 → 身份 → 关系"的顺序问过一遍？ | ✅ §4 |
| 有没有把蓝色（定义）当粉色（事实）？ | ✅ 已列出：`conversation_teaching_reply.english_level` 是粉色；`EnglishLevelEnum` 才是蓝色 |
| 有没有把粉色（事实）当蓝色？ | ✅ `ScenarioVO.level` 曾以 String 表达分类法，已要求类型化 |
| 有没有把黄色（角色）当绿色（参与方）？ | ✅ `deviceId` 归属问题（§5.7） |
| 每个聚合根是否都有粉色锚点？ | ✅ §7.2（`content` 与 `companion` 明确**没有**聚合根） |
| 有没有为了"一个功能"造了一个领域？ | ✅ 世界定为 7 个，判据在 §6.5；`companion` 被**收缩**而不是扩张 |

### 10.2 业务规则不丢失自检

| 规则族 | 新归属 | 状态 |
| --- | --- | --- |
| 会话状态机 S1 / 开场一次 S2 / 首句冻结 S3 | coaching.session | 保留 |
| 回复归属 S4 / 完成一次 S5 / 取消拒收 S6 / 主体不可变 S7 | coaching.session（`ReplyBinding`） | 保留并显式化 |
| 单槽 S8 / 一用户一会话 S9 | media / coaching | 保留（S8 显式建模） |
| 档位演进 L1~L2 | learning.level（`LearnerLevelTracker`） | 保留（位置不变） |
| 升级门槛 L3 / 手动优先 L4 / 近期口径 L5 | learning.level | 保留 |
| 跟读过关 R1 / 纠音上限 R2 / 聊天重试上限 R3 | coaching.teaching | 保留 |
| 错词可信度 R4 / 近期避重 R5 / 生成校验 R6 | coaching.teaching | 保留 |
| 上下文预算 C1 | companion | 保留 |
| 记忆明确性 M1 / 遗忘边界 M2 | learning.memory + learning.level | **同域同事务**（修正 V4） |
| 练习去重 P1 / 邀请真播完 P2 | coaching.practice | 保留 |
| 学习请求 T1 | coaching.practice | 保留 |
| 观察格式 A1 | assessment.expression | 保留 |
| 零评判 / 必追问（人格契约） | companion 提示词 + coaching.teaching 策略 | 保留（唯一可观测手段仍是回复原文日志） |
| 档位不上界面 | 边界层 DTO 不含档位 | 保留（§10.3） |

### 10.3 可见性红线自检

| 红线 | 落点 |
| --- | --- |
| 档位不出现在响应 DTO | `OpeningDecisionVO` 注释已声明；新增 `MediaLease` / `LearnerProfileAggregate` 同样不得外泄 |
| 评测分不进入用户可见文本 | 评测数据只进提示词与发音卡片 |
| 画像维度有 `visible` 白名单 | 归 `learning`（§7.6-1 选定一套后必须保留该语义） |

### 10.4 本文件未解决 / 需产品拍板

1. **匿名 → 登录是否归并学习成果**（§5.7）。四色建模只能把主体解析集中，不能替产品决定。
2. **收尾逻辑（M15~M17）的产品口径**：总结里保存什么、保留期多长（`user-data-model.md` §8.6 仍未定）。
3. **场景阶段（`conversation_scenario_stage`）是否启用**：表在、种子数据在，代码未用。
4. **`conversation_summary` / `user_learning_stat` 与 V12 证据模型的关系**：汇总表是读模型还是事实表？
   本文件的倾向是**读模型**（可由证据重算），因此它不属于任何聚合根，只在 Case 里维护。
5. **`llmOwner=OPENTALKING` 这条链路还保不保留？** 它是迁移前的兜底开关，
   但两条链路的领域事实必须一致（都写同一套会话/回合/评测）。若确定永不回退，应删除该分支以消除"双事实"。
   （§2.2d）
6. **`practice=true` 时的删除行为**：现在练习输入**不能新增**事实，却能**删掉**已有事实
   （`LongTermMemoryService` 的删除分支在 `practice` 判断之前）。这是有意还是遗漏？（§2.2b）
7. **`IConversationCase.create(...)` 的处置**：它没有调用方（Controller 走 `SessionCreationCase`）。
   是补回调用，还是删除接口方法？（§2.7）

### 10.5 已修正的事实错误（保留痕迹，供后续复核对照）
本节列出一次对抗式复核发现、并已在本文件修正的 14 处问题，避免它们再次出现：

| # | 原稿错误 | 事实 | 修正处 |
| --- | --- | --- | --- |
| 1 | `user_device` / `user_login_log` / `conversation_scenario_stage` 属于"真被读写" | 三张表**没有任何生产代码**读写 | §2.4 A/C |
| 2 | `user_practice_plan` "完全没有代码引用" | 被 `OpeningFactsMapper.plan` 读（`OpeningPlanRepository` → `OpeningPreparation`） | §2.4 A/C |
| 3 | 引用常量 `WARN/STRONG_SCORE` 并把阈值归评估域 | `WARN_SCORE` **不存在**；`WEAK_SCORE`/`STRONG_SCORE` 是 `LearnerLevelTracker` 的私有常量 | §4.3 |
| 4 | `training_session.device_id` 仍是 NOT NULL | `VARCHAR(128) DEFAULT NULL`（V1 起） | §5.7 |
| 5 | `closeSessionsOfUser` 存在 | 只有 `closeSessionsOfDevice`；按用户收敛是**提议** | §2.2 S9 |
| 6 | 心跳 15 秒在 Case 层 | 在 **Trigger 层** `SessionController` | §2.2b |
| 7 | 开场"恰好 1 个问号" | 默认开场如此；**FIRST 开场要求不含问号** | §2.2b |
| 8 | `IOpeningIdentityPort` 在 coaching | 在 **identity.adapter.port**（与 §2.5/§7.4 自相矛盾） | §8.1 |
| 9 | `LongTermMemoryEntity` 在 `companion.service.memory` | 在 `companion.model.entity` | §8.1 |
| 10 | `IChatService` 在 `companion.service.chat` | 在 `companion.service` | §8.1 |
| 11 | 遗忘边界由 learning 读写 | 由 **identity 的 `IAccountProfileDao`** 与 **companion 的 `CompanionMemoryMapper`** 写 | §3.2 V4 |
| 12 | `learner_profile` 只有 2 个 Mapper 涉及 | 4 个（另含 `OpeningFactsMapper`、`CompanionMemoryMapper`） | §2.4 A |
| 13 | "约 20 个 policy" | coaching 下 `*Policy` 类恰好 **9** 个（全项目 13 个） | §2.5 |
| 14 | `SessionStatusEnum` "其实是媒体事实"、`practice=true` "从不写" | 前者改为标注为**解释**；后者补"删除分支在 practice 判断之前" | §6.3 / §2.2b |

---

## §11 专项评估：`cases/conversation` 这一层

> 这一节是对 §6 / §7 的**落地校准**。理由：本设计的 `media` 领域抽取，
> 真正的工作面不在 domain，而在 `cases/conversation/runtime/ConversationServiceImpl`。
> 不把这层看清楚，§9 的步骤 3 一定做歪。
>
> 证据等级：**已核对事实**（逐行读过 `ConversationCaseImpl`、`ConversationServiceImpl`、
> `IConversationService`、`MediaRuntimePort`、`ConversationTurnAggregate`、
> `ConversationSessionRepository`、`SessionController`）；判断部分已标注。

### 11.1 这一层有什么

32 个文件，两个包根 `cases.conversation`（含 `impl/workflow/runtime/translation/command/result/model/port.out`）与 `cases.practice`、`cases.overview`。

| 分组 | 类 | 评价 |
| --- | --- | --- |
| 门面 | `IConversationCase` + `ConversationCaseImpl`（37 行） | **做对了**：纯转发，无逻辑 |
| 工作流（单一职责，各管一件事） | `SessionLifecycleWorkflow`、`ReplyWorkflow`、`ReplyCancellationWorkflow`、`SessionEventsQuery` | **做对了**：这轮重构最成功的一处 |
| 出站端口（Case 自己定义的） | `port/out/MediaRuntimePort` | **方向对**：让媒体运行态可替换 |
| 运行态与事件管线 | `runtime/IConversationService` + `runtime/ConversationServiceImpl`（**624 行**） | **问题集中在这里** |
| 回执端口 | `runtime/IReplyCompletionPort`、由 `cases.practice.PracticePlaybackCase` 实现 | 方向对（解开循环依赖），但装配方式有害 |
| 准备 helper（被三个 workflow 调用） | `impl/OpeningPreparation`、`impl/TeachingPreparation`、`impl/AgentReplyPreparation`、`impl/SessionCreationCase`、`impl/GuidedReadingCase` | 职责合理，**包位置错**（§11.4） |
| 转换器 | `impl/ConversationAssembler` | 它其实是 `runtime` 侧的映射器，放错包 |
| 翻译 | `translation/IConversationTranslationService` + impl | 合理 |

### 11.2 三个"层与名"的错位（这是最本质的问题）

**① `IConversationService` / `ConversationServiceImpl` 被注入了领域仓储，却住在 Case 层。**

位置：`cases/conversation/runtime/`；`ConversationServiceImpl` 的构造器直接注入
`IAvatarDriverPort`、`IConversationSessionRepository`、`IConversationTurnRepository`、`IConversationEventBusPort`，
并用 `@Resource` 注入 `ITeachingReplyRepository`、`IOpeningEncounterRepository`。

它的接口 `IConversationService` 的 javadoc 自己写着"约定 **Case 内**的媒体运行流程"，
而 `ConversationServiceImpl` 的类注释写着"跨能力运行流程放在 Case；**任务和状态规则保留于领域对象**"。
问题是：`applyDriverEvent` 里既有"状态推进"，也有"开场相遇登记 + 教学任务提交 + 界面事件发布 + 完成回执"。

**② 名字骗人：`*ServiceImpl` 其实是媒体运行 + 回合归档。**

真正的用例门面叫 `ConversationCaseImpl`，而这个 624 行的类叫 `ConversationServiceImpl`
（历史命名，来自 `spoken-ai` 迁入时的 `ConversationService`）。
`IConversationService.requireSession` 的 javadoc 甚至自己承认："本方法**只做这一件事**"——
一个"Service"需要专门声明自己只做一件事，正说明它的边界已经模糊。

**③ 接口 javadoc 与实现互相矛盾（这是本项目特有的高成本问题）。**

| 位置 | javadoc 说什么 | 代码做什么 |
| --- | --- | --- |
| `IConversationService.refreshStatus` | "向上游纠偏一次会话状态（**只在本地还是 QUEUED 时才问**）" | `ConversationServiceImpl.refreshStatus` 只在"**已经收尾**"时跳过，QUEUED/READY/ACTIVE 全问；实现里的注释还专门解释"READY/ACTIVE 也必须问 —— 这是原来漏掉的一档" |
| `IConversationService.requireSession` | "只做这一件事 —— 不向上游纠偏状态" | 实现确实只查仓储；但**接口的 `createSession`/`exchangeOffer` 文档却写"等待策略由用例层决定""纠偏状态"，而纠偏实际发生在同一个类里** |
| `SessionController` 类注释第 53 行 | `conversationCase.create(request) --> SessionResponseDTO` | 实际调的是 `creation.create(request)`（`SessionCreationCase`），`conversationCase.create` **全仓无调用方** |

> 这个项目把注释当契约（本仓库每一处改动都同步注释），所以**注释漂移的代价比别处高**：
> 下一个接手的人会按 `refreshStatus` 的接口文档去理解状态纠偏，然后写错。

### 11.3 `ConversationServiceImpl` 内部的具体问题（按严重度）

| # | 问题 | 位置 | 为什么是问题 |
| --- | --- | --- | --- |
| 1 | **`applyDriverEvent` 是跨领域分支机**：一个方法 ≈80 行、6 个 `switch` 分支，里同时做媒体状态推进、回合归档、开场相遇登记、教学任务提交、`READING_CHANGED` 发布、`IReplyCompletionPort` 回执 | L443–522 | 前 3 项属 `media`/`coaching.session`，后 3 项属 `coaching.teaching`/`coaching.practice`。**它不是一个"领域服务"，它是两个领域的合流点**——这是 §6.3 判决的最直接证据 |
| 2 | **"猜最后一条回合"仍在，而且有真实竞态** | `beginReply` L549–563（回退分支 L556–558）；`completeAssistantTurn` L574–576 | 聚合注释说"完成后不再读'最后一条回合'猜归属"，但 `beginReply` 在 `replyId==null`（`speak` 路径 L269）或 replyId 不匹配时，会用 `filter(userText != null && assistantText == null)` **猜**一个 turnId。竞态路径：上一轮回复 r1 尚未 `assistant.done`，此时来了 `speak`（`beginReply(null, true)`）→ `session.beginReply` **无条件重置 `replyTurnBound=false`** → r1 的 done 到达 → `completeReplyOnce()` 通过（因为 `beginReply` 也重置了 `completed`）→ `completeAssistantTurn` 因 `!replyTurnBound()` 而猜到一个**新的**未答回合 → r1 的回复被**挂到错回合**。正确做法：绑不到归属就**拒绝归档**（保留原绑定），而不是重置后猜 |

**11.3 补充：`beginReply` 的重置语义本身值得讨论。** 它一次重置四样东西：
`activeReplyId`、`replyTurnId`、`replyTurnBound`、`replyCompleted`。
前两样"开始新一轮"是对的，后两样是**完成标记**——
在新一轮开始时把上一轮的"已完成"标记抹掉，等于允许同一轮的 done 被处理两次。
更安全的做法是把完成标记绑到 `replyId` 而不是会话字段（即 §8.2 的 `ReplyBinding(replyId, turnId, generation)`）。
| 3 | **`nextSeq = findLastBySessionId().seq + 1` 是读-改-写竞态**，靠 `synchronized(session)` 保护 | `appendUserTurn` L540–545 | 进程内可用（`ConversationSessionRepository.bySessionId` 是 `ConcurrentHashMap`，同一 sessionId 返回**同一个聚合实例**，已核对），所以锁是有效的。但这是**进程内锁**，多实例部署直接失效；V9 已经设计了 `next_message_seq` 递增序号正是为了修这个 |
| 4 | **事件总线发布在会话锁内** | `applyDriverEvent` L504 + `sourceDriverEvents` 的 `synchronized(session)` L418 | `InProcessConversationEventBus` 用 `multicast().directBestEffort()`，`tryEmitNext` 是**同步**调用订阅者。SSE 订阅者若阻塞，会**持有会话锁**，进而阻塞所有媒体事件与请求线程。这是真实运行风险 |
| 5 | **字段注入 + 可空**：`@Autowired(required=false) IReplyCompletionPort` | L52–53 | 装配失败就静默丢功能（练习邀请永不激活），且 624 行的类无法构造器单测 |
| 6 | **注释与实现矛盾**（同类问题） | `requireSession` L165–171 说"不向上游纠偏"，`createSession` L124 说"流程里没有『等』：由**用例层**去轮询" | 但轮询、纠偏、归档全在同一个类里 —— 注释描述的是一个**已经不存在的分层** |
| 7 | 状态机有两个写入口 | `refreshStatus`（轮询路径 L198）与 `applyDriverEvent`（事件路径 L446/448/454） | 两条路径都 `markStatus` + `update`，规则分散。应收敛到**一个**领域服务方法，Case 只调用 |

### 11.4 包结构的具体问题

```
cases/conversation/
├── impl/          ConversationCaseImpl（门面 ✓）
│                  OpeningPreparation / TeachingPreparation / AgentReplyPreparation   ← helper，不是 impl
│                  SessionCreationCase / GuidedReadingCase                            ← 平级用例，不是 impl
│                  ConversationAssembler                                             ← runtime 侧映射器
├── runtime/       IConversationService + ConversationServiceImpl  ← 名字是 runtime，内容是领域服务
│                  IReplyCompletionPort
├── port/out/      MediaRuntimePort  ✓
└── translation/   ✓
```

两个命名不一致值得指出：
- Case 层有两个"出站"位置：`port/out/MediaRuntimePort`（对）和 `runtime/IConversationService`（它其实是**应用服务**，不是端口）。
- helper 与用例混在 `impl/`：`impl/` 里同时有"门面实现"和"被编排的组件"，读目录读不出层次。

### 11.5 我的处理意见（相对于 §7 蓝图的修正）

**不推翻，只细化。** §7.3 列了 Case 归属，但没有说明
`ConversationServiceImpl` 这 624 行**怎么拆**。现在补上：

| 现在的职责 | 应归 | 理由 |
| --- | --- | --- |
| 上游句柄生命周期：`createSession` / `closeSession` / `refreshStatus` / `exchangeOffer` / `iceServers` | **`media` 领域服务** | 全是"上游资源 + 状态"，不含我方业务规则（S8 单槽） |
| 事件协议规整：`asText` / `asFullText` / 事件类型分支 | **`media`**（媒体侧）+ **`coaching.session`**（会话侧） | 规整形状属媒体边界；对聚合的影响属会话 |
| 回合归档：`appendUserTurn` / `beginReply` / `completeAssistantTurn` | **`coaching.session` 领域服务** | 这是 S4/S5 不变式的实现，必须在领域 |
| 教学完成：`pendingTeaching` 提交、`commitSpoken`、`READING_CHANGED` | **`coaching.teaching`** | `TeachingPreparation.complete(...)` 已有一条实现（**流式播报路径**）；`applyDriverEvent` 是**整段 `/say` 路径**的另一条（调用点 L498–504 + `READING_CHANGED` L504，已核对）。**规则是同一条（都用 `acceptsReply` + `commitSpoken`），实现有两处** —— 属于"同一规则两个入口"，应抽成一个领域服务方法供两条路径调用 |
| 开场相遇登记 | **`coaching.session`**（`OpeningEncounterAggregate`，§7.2 已列为独立聚合） | 现状写在此处，与"开场决策"分家 |
| 完成回执分发 | **保留在 Case**（它就是跨域协调） | `IReplyCompletionPort` 的方向是对的，只需改成构造器注入 |

**三条具体建议**（按性价比排序）：

1. **先修注释与实现的一致性 + 删死接口**（零风险、立刻可做）：
   `IConversationService.refreshStatus` 的 javadoc 改成与实现一致；
   `IConversationCase.create(...)` 要么补回调用、要么删除；`SessionController` 第 53 行的注释改对。
   > 这一步不改任何行为，但它消除的是本仓库最贵的成本：**注释就是契约**。
2. **把 `@Autowired(required=false)` 改成构造器注入 + 显式的 no-op 实现**：现在装配失败会静默丢功能。
3. **把 `applyDriverEvent` 的分支按领域切开**：媒体状态推进留在 `media`，
   回合归档与教学提交下沉到 `coaching.session` / `coaching.teaching` 的领域服务，
   `ConversationServiceImpl` 退化成"事件路由 + 回执分发"。
   这一步是 §9 步骤 3 的**真正内容**，也是风险最高的一步，必须在有回归保护时做。

> **对 §9 步骤 3 的修正**：原步骤写的是"抽出 media 领域 + 会话改用 `MediaLease`"。
> 评估后应改为：**先做 11.5-1（注释与死接口），再做 11.5-2（注入），最后才做领域搬迁**。
> 顺序反了会让"搬迁"与"重构"混在一次 diff 里，无法判断回归失败是哪一边造成的。



---

## 附：一张图

```
                      ┌───────────────────────────────────────────┐
   绿色/黄色           │  identity:  账号 · 凭证 · 自述 · 已保存选择  │
   "谁在练"           └──────────────────┬────────────────────────┘
                                        │ LearnerId（可信主体）
                      ┌─────────────────▼──────────────────────────┐
   蓝色/绿色           │  content:  场景定义 · 分档开场白 · 教材目录   │
   "练什么"            └─────────────────┬──────────────────────────┘
                                        │
   ┌────────────────────────────────────▼──────────────────────────────────┐
   │  coaching（粉色为主）                                                   │
   │    session:  会话时段 · 用户回合 · 助手回复 · 播报完成 · 首句决策          │
   │    practice: 练习模式 · 待播任务 · 邀请激活                             │
   │    teaching: 教学规则 · 跟读规则 · 提示词策略（蓝）                      │
   │    history:  历史读模型（无不变式）                                     │
   └───┬───────────────────┬───────────────────┬───────────────────────┬───┘
       │ 送录音             │ 读证据            │ 读上下文               │ 下发文本/收事件
       ▼                   ▼                   ▼                       ▼
 ┌───────────┐      ┌───────────┐      ┌────────────┐         ┌──────────────┐
 │assessment │      │ learning  │      │ companion  │         │    media     │
 │ 粉：证据   │      │ 黄绿粉：   │      │ 读模型 +    │         │ 粉：媒体会话  │
 │ 发音/观察  │      │ 等级·记忆  │      │ 提示词装配  │         │ 单槽租约·回执 │
 └───────────┘      └───────────┘      └────────────┘         └──────────────┘
   只产证据            只消费证据           只读                    只被 coaching 用
```

**一句话结论**：这次重构的实质不是"把包重新摆一遍"，而是
**把"文本生成完成"和"媒体播放完成"这两个被混在一起的粉色事实分开**，
**把"学习者的记忆"从"伙伴"手里交还给"学习者自己"**，
**把单槽媒体资源从会话状态机里摘出来显式建模** ——
其余五个领域的边界方向是对的，保留并收紧。

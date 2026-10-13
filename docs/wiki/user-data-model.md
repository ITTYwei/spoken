# 用户数据模型（账号 · 画像 · 对话策划 · 对话记录）

> **这份文件是表结构的评审说明**：讲「加了哪些表、为什么这么加、要付什么代价」。
> 字段级的权威定义在迁移脚本里，本页不复制 DDL（避免两处漂移）：
> [`V4__user_account.sql`](../../spoken-server-app/src/main/resources/db/migration/V4__user_account.sql)、
> [`V5__user_profile.sql`](../../spoken-server-app/src/main/resources/db/migration/V5__user_profile.sql)、
> [`V6__conversation_plan.sql`](../../spoken-server-app/src/main/resources/db/migration/V6__conversation_plan.sql)、
> [`V7__conversation_message.sql`](../../spoken-server-app/src/main/resources/db/migration/V7__conversation_message.sql)。
>
> 产品取向（零评判 / 追问 / 分级语言）见 [`product-design.md`](./product-design.md)；
> 工程架构与踩坑见 [`llm-wiki.md`](./llm-wiki.md)。
> 最后更新：2026-10-12。V9 修复约束，V10 统一全部业务表的 DATETIME 审计字段，见 §9、§10。
> **实现状态**：账号、消息、画像及收尾 worker 尚未接入；当前 Java 仍写 `conversation_turn`。

---

## 1. 为什么要做这件事

V1~V3 时代全库**只有 3 张表、没有任何用户表**：`training_session.device_id` 是唯一的「谁」，
[`CreateConversationSessionRequestDTO.java`](../../spoken-server-api/src/main/java/cn/adinall/ai/api/dto/CreateConversationSessionRequestDTO.java)
里还明写着「MVP 阶段不接账号体系」。

这带来两个已经能感觉到的损失：

1. **画像无处可存**。学员的水平档位（`LearnerLevelTracker`）是**纯内存**的，会话一结束就蒸发 ——
   它每天算出来的东西正是画像最值钱的原料，现在全丢了。
2. **练过的东西找不回来**。换台设备就等于换个人，历史对话与评测分归零。

所以这一步的实质是：**把身份从「设备」升级到「用户」**，并让画像、策划、记录都挂在人身上。

---

## 2. 本次拍板的 6 个决策（连着代价一起看）

| # | 决策 | 代价 / 连带影响 |
|---|---|---|
| 1 | **完整账号一次到位**：手机号 / 微信 / Apple 多方式登录 | 要处理凭证管理、手机号加密、登录审计；所有会话接口要加鉴权 |
| 2 | **不做匿名，必须登录才能用** | 首次打开多一道登录墙（流失风险）。**换来的是**：不存在「匿名→正式账号」的合并逻辑，砍掉 `user_merge_log` 与整套并账规则 —— 这是刻意的简化 |
| 3 | **画像混合存储**：自述一张表 + 系统推断一张键值表 | 两张表之间要定「谁说了算」：冲突时以**自述**为准影响体验 |
| 4 | **对话记录改为「一句话一行」**（message 取代 turn 的文本职责） | ⚠️ **唯一的破坏性契约变更**：`turnId` 关联键要改（见 §5.2） |
| 5 | **「对话策划」四件事全要** | 表从 1 张变 5 张（剧本 + 阶段 + 计划 + 计划条目 + 会话目标列） |
| 6 | **长期水平决定开场语言**：有历史就用长期水平当会话初始档位（长期 LOW → 开场用中文），开场白按档位存多份 | 长期水平必须按**近期**表现算，否则一次糟糕经历会把人钉死在 LOW；且档位仍然不上界面。见 §4.2 |

---

## 3. 总览：13 张新表 + 3 张改造

| 迁移 | 表 | 归属键 | 一句话用途 | 优先级 |
|---|---|---|---|---|
| V4 | `app_user` | — | 账号主体（不含凭证、不含手机号） | 核心 |
| V4 | `user_identity` | `user_id` | 登录凭证（手机号/微信/Apple 可挂同一账号） | 核心 |
| V4 | `user_device` | `user_id` | 登录设备（多端管理 + deviceId→userId 换算） | 核心 |
| V4 | `user_login_log` | `user_id` | 登录审计（排查「用户说登不上」） | 核心 |
| V5 | `user_profile` | `user_id`(PK) | 画像-**自述**（用户明确说的，视为事实） | 核心 |
| V5 | `user_profile_item` | `user_id` | 画像-**推断**（键值 + 置信度 + 证据） | 核心 |
| V5 | `user_learning_stat` | `user_id`(PK) | 跨会话累计（会话数/天数/均分/长期水平） | 核心 |
| V6 | `conversation_scenario` | `owner_user_id` | 剧本站（官方内置 + 用户自定义） | 核心 |
| V6 | `conversation_scenario_stage` | `scenario_id` | 多阶段推进路线 | 核心 |
| V6 | `user_practice_plan` | `user_id` | 练习计划 / 学习目标 | 核心 |
| V6 | `user_practice_plan_item` | `plan_id` | 计划条目（「今天练什么」） | 核心 |
| V7 | `conversation_message` | `user_id` | 对话记录（一句话一行） | 核心 |
| V7 | `conversation_summary` | `user_id` | 会话总结 = 记录→画像的原料桥 | 核心 |
| V7 | `training_session`（改造） | 加 `user_id` | 会话归属 + 单场目标 + 剧本快照 | 核心 |
| V7 | `pronunciation_assessment`（改造） | 加 `message_id` | 评测改挂到「用户那条消息」 | 核心 |
| V8 | `conversation_scenario`（改造） | 加 3 列 | 开场白按档位分文本（长期 LOW → 中文开场） | 核心 |

> **V8 为什么单独一个迁移、而不是直接改 V6**：V6 可能已经被应用过，
> 而 Flyway 会校验已应用脚本的 checksum —— 改一个字符服务就起不来。
> 用「新增迁移 + ALTER + UPDATE」的方式，**无论 V6 有没有被应用过都能正确执行，且不需要 `flyway repair`**。
> 这是这个项目里的通用规则：**已应用的迁移文件一律不改，改动一律新开版本。**

`conversation_turn` 表**不删，当前仍在写入**。V7 的「已停用」注释早于代码切换，V9 已纠正。
完成 §5 的 Java/Android 联动切换后再归档；旧行没有用户归属，不能仅凭设备 id 自动归给新账号。

### 关系图（→ 表示「属于」）

```
app_user ──┬── user_identity         (1:N, 多种登录方式)
           ├── user_device           (1:N, 多端登录)
           ├── user_login_log        (1:N, 审计)
           ├── user_profile          (1:1, 自述)
           ├── user_profile_item     (1:N, 推断)
           ├── user_learning_stat    (1:1, 汇总)
           ├── user_practice_plan ── user_practice_plan_item  (1:N)
           ├── conversation_scenario (owner_user_id=NULL 即官方)
           │        └── conversation_scenario_stage           (1:N)
           ├── training_session ──┬── conversation_message    (1:N, 一句话一行)
           │                      └── conversation_summary    (1:1)
           └── pronunciation_assessment → conversation_message (挂到用户那条)
```

---

## 4. 关键设计决策（只讲不显然的）

### 4.1 画像为什么拆两张表

| | 存哪 | 语义 | 冲突时 |
|---|---|---|---|
| **用户自述** | `user_profile` | 事实，改一次就覆盖 | **以它为准**影响体验 |
| **系统推断** | `user_profile_item` | 概率，带 `confidence` + `evidence_json` | 服从自述 |

拆开的三条理由：
1. **可信度不同**。用户说「我英语还行」是事实陈述；系统从一次 52 分推出「他很弱」是概率。
2. **会漂移**。兴趣、薄弱音、常错词都会变，而且维度会一直加 —— 所以用键值表 + 唯一的
   `(user_id, item_key, item_value)`，加维度不用 ALTER。
3. **必须可推翻**。画像要能回答「你凭什么这么判我」（`evidence_json` 指向
   session / message / assessment）。一个不能被质疑的画像，与「不做评判」的产品取向相悖。

> **纯宽表**增加维度通常需要 ALTER；JSON 也可以查询、排序并针对固定路径建索引，
> 但动态维度跨用户检索更复杂。键值表便于直接按 `(item_key, item_value)` 检索。
> V9 已把 191 字符前缀唯一索引换成完整值唯一索引，避免不同长值误命中 upsert。

### 4.2 长期水平决定**开场语言**（会话初始档位）

> 这一条**改过**：早先写的是「长期水平不许当初始档位」，那是把「零评判」误用成了「不许利用历史」。
> 正确做法是：**用历史当起点，但按近期表现算**。下面是改后的规则。

`user_learning_stat.long_term_level` 有两个用途，第二个是这次补上的：

| 情况 | 会话初始档位 | 开场白 |
|---|---|---|
| **有用户明确自评**（`self_level` 有效） | beginner → LOW / intermediate → MID / advanced → HIGH | 按自评档位选；只决定起点 |
| **无自评，有可用近期历史** | 用近期长期水平 | 按该档位选；证据要求见 §9.4 |
| **两者都没有** | 从场景难度起步（`advanced` → HIGH，其余 MID） | 按该档位选 |

用途 1（不变）：挑内容难度 —— 话题、句长。

会话内仍由 `LearnerLevelTracker` 逐轮演进（下调立刻、上调攒 2 轮、一次只挪一档）——
长期水平**只决定起点**，不封顶学员在会话里的爬升。

**这不违反「零评判」，但界线要说清：**

- 原红线真正反对的是「**没有证据就假定**学员不行」—— 人还没开口、只因为场景写着 beginner 就按 LOW 招呼。
- 长期水平是**学员自己的历史表现**攒出来的，拿它当起点是**连续性**，不是预判。
  让一个长期撑不住的学员一上来就听全英文，才是真正的不友好 —— 他会沉默，或者逃回中文。
- 两条护栏必须同时有，否则就会滑回「评判」：
  1. **长期水平按近期表现算**（滚动窗口 / 最近 N 场），不背历史总账。
     否则一次糟糕经历会把人**永久钉在 LOW**，那才是真的判了刑。
  2. **档位仍然不出现在界面上**（见 [`product-design.md`](./product-design.md) §3.5）。
     开场说中文是「接住他」，不是「告诉他你很差」。

### 4.2.1 因此开场白必须**按档位存多份**

这一点最容易漏：**开场白走脚本轨 `/say`，不经 LLM** —— 没有任何模型能替它适配档位。
原来一个场景只有一句 `opening_line`，档位再低也只能照念那句英文。

所以 `conversation_scenario` 改成按档位存（DDL 在
[`V8__scenario_opening_by_level.sql`](../../spoken-server-app/src/main/resources/db/migration/V8__scenario_opening_by_level.sql)）：

```
优先级：档位专属开场白  →  opening_line（默认兜底）  →  都没有 = 不自动开场（no_opening）
```

| 列 | 内容 |
|---|---|
| `opening_line_low` | 主要中文 + 一句 3~6 词英文示范 + 中文邀请跟读（与 LOW 的语言策略一致） |
| `opening_line_mid` | 简单英文短句 |
| `opening_line_high` | 全英文 |
| `opening_line` | 默认兜底（旧数据与「只想写一句」的场景用） |

> **备选方案**：如果以后档位会变多、或开场要支持多语言，就把这三列换成子表
> `conversation_scenario_opening(scenario_id, level, lang, content)`。
> 现在档位是代码里的三值枚举，三列更简单、读路径也不用 join。
> **附带风险**：中文开场由同一个英文音色（Cherry / Ethan）念 —— 百炼 Qwen TTS 是多语言的，
> 预计没问题；若听感不对，下一个旋钮就是「按档位配 voice」。

另外（不变）：`user_profile_item` 里所有水平类维度的 `visible` 必须为 0，不许出给 App。

### 4.3 `conversation_scenario` 必须同时有自增 id 和 `scenario_code`

现有对外契约用的是**字符串场景 id**（`ScenarioVO.id` = `'free'` / `'interview'`，
`training_session.scenario_id` 是 `VARCHAR(64)`）。新表如果只有自增主键就会破坏这个契约，
所以另加一列唯一的 `scenario_code`，对外一律用它。

### 4.4 会话必须快照剧本（`scenario_snapshot_json`）

用户改了自定义剧本之后，旧会话的复盘会变得莫名其妙 ——
**当时是按 A 剧本聊的，现在读出来是 B**。所以建会话时把剧本（人设/开场白/阶段）快照进会话档案。

轻方案优先：不做 `scenario_version` 版本表，等真要「剧本编辑历史 / 回滚 / 多人协作」再上。

### 4.5 阶段推进状态**不落库**

「这场剧本走到第几阶段了」和学习者档位一样，是**会话内的运行态**，
本版放内存、不落库（会话本身就是短生命周期的，重启即丢）。
除非要做「中途退出、回来接着练」，才需要给会话加 `current_stage_seq`。

### 4.6 `conversation_summary` 是画像的原料桥

一场聊完从本场抽出兴趣点与薄弱点 → 回填 `user_profile_item`；同时给复盘页提供人话总结。
没有这张表，画像就只能靠「每轮实时推断」，既噪声大又难追溯。

### 4.7 沿用现有约定（刻意不引入新风格）

- MySQL 8、`utf8mb4` / `utf8mb4_unicode_ci`、**每列写 COMMENT**。
- 所有业务表的 `created_at`、`updated_at` 统一 `DATETIME`，分别默认创建时间和自动更新时间（V10）。
  生命周期、租约等业务时间字段暂沿用原定义；DATE 表示练习日期，不是事件时间。
- **不建外键约束**（现有表也没有）：关联只建索引，完整性由应用层保证。
- 新表主键 `BIGINT AUTO_INCREMENT`；对外用 `user_no` 这类**不可枚举**的编号列，不暴露自增 id。
- 敏感字段拆分：手机号不明文入库（`provider_uid` 存哈希，`secret_encrypted` 存密文）。
- 加列一律**可空**、`ALTER` **不用 `AFTER`**（避免同一条语句内引用刚加的列）；
  列顺序不影响功能。

---

## 5. 连锁改动清单（本版只出表，代码另开一轮）

### 5.1 会话与场景

- `closeSessionsOfDevice` → **`closeSessionsOfUser`**：单 GPU 槽约束下的规则从
  「一台设备只留一场会话」改为「**一个用户只留一场会话**」；
  `IConversationSessionRepository.findByDeviceId` 相应改为按 `user_id` 查。
- `InMemoryScenarioRepository` → **DB 实现**：插入点现成（`IScenarioRepository` 端口 +
  `DefaultBeansConfiguration` 上的 `@ConditionalOnMissingBean`），调用方一行不用改。
  但 `findById` 现在「查不到就回退第一个场景」，接 DB 后这个兜底行为要重新定义。
- `training_session.user_id` 从**登录态**取，不再从请求体取；
  同时要落 `brief` 与 `scenario_snapshot_json`。

### 5.2 ⚠️ `turnId` → `messageId`（唯一的破坏性变更）

跟读评测的关联键从 `conversation_turn.id` 改为「**用户那条消息**的 id」
（`pronunciation_assessment.message_id`）。要同步改的地方：

| 位置 | 现状 | 改后 |
|---|---|---|
| 领域事件 | `stt.final` 的 data 带 `turnId` | 带 `messageId` |
| HTTP 响应 | `POST /voice` 返回 `{text, turnId, assessment}` | `{text, messageId, assessment}` |
| 评测接口 | 落 `pronunciation_assessment.turn_id` | 落 `message_id`（`turn_id` 列保留给旧数据） |
| Android | `pendingAssessment` 以 turnId 为键、评测卡片挂到气泡 | 改按 messageId |

> 兼容期可同时返回两个字段，但 `turnId` 必须是真实旧回合的 id，`messageId` 是新消息的 id。
> 两张表独立自增，不能假定二者相等，也不能把 messageId 填进 turn_id；评测兼容写入时分别关联。
> 统一切换后再移除旧键；需要逐会话开关，不能让同一会话混用两种存储语义。

### 5.3 鉴权与画像

- 所有 `/api/v1/sessions/**`、`/api/v1/pronunciation/**` 加登录校验；
  新增登录/注册/刷新接口（手机号 + 微信/Apple）。
- 新领域端口：`IUserProfileRepository` / `IUserLearningStatRepository` /
  `IPracticePlanRepository` 等（DDD 端口-适配器，与 `IPronunciationPort` 同构）。
- **`LearnerLevelTracker` 的信号要回写**：会话结束时把本场的档位信号、评测均值、
  兴趣点/薄弱点写进 `user_profile_item` 与 `user_learning_stat`。
- **反过来要读**：建会话时先用用户明确自评，其次用有近期证据的 `user_learning_stat.long_term_level` 当初始档位，
  取不到才回退到场景难度。`ConversationServiceImpl.initialLevel` 现在只接收 `scenarioId`
  （只读场景），要给它加一个学习汇总的入参 —— 见 §4.2。
- **开场白要按档位选**：`ConversationCaseImpl.opening()` 现在直接取 `scenario.openingLine()`，
  要改成「按当前档位取 `opening_line_low/mid/high` → 兜底 `opening_line`」。
  注意 `ScenarioVO` 这个领域值对象要跟着加字段 —— 见 §4.2.1。
- 会话收口有两个入口（`closeSession` 与 `SESSION_CLOSED` 事件都会走到 `markClosed`），
  「生成总结 + 回写统计」**必须幂等**，否则会重复累计。

---

## 6. 隐私与合规约定（表结构已经体现）

| 约定 | 落点 |
|---|---|
| 手机号不明文入库 | `user_identity.provider_uid`（哈希）+ `secret_encrypted`（密文） |
| 原始音频**默认不落**（敏感 + 体积） | `conversation_message.audio_key` 预留，默认 NULL；要存就上对象存储 |
| 画像可追溯、可推翻 | `user_profile_item.evidence_json` + `visible` 白名单 |
| 注销 | `app_user.deleted_at` 软删；`user_identity` / `user_practice_plan` / `conversation_scenario` 同样有软删列 |
| 登录日志有保留期 | 建议 90 天物理清理（`user_login_log.created_at` 上有索引，便于批量删） |
| 对话记录量大，用保留期而非软删 | `conversation_message` 不设 `deleted_at`；清理按 `created_at` 批量删 |

---

## 7. 落地顺序与安全边界

1. **可以先执行，不会影响现有服务**：V4~V8 全是「建新表」或「加可空列」，
   没有任何 DROP、没有改非空约束、没有改列类型。唯一例外是
   `training_session.message_count` 是 `NOT NULL DEFAULT 0`（有默认值，历史行安全）。
2. ⚠️ **重启服务就会自动执行**：`flyway.enabled: true`，下一次启动 Flyway 会把 V4~V8 跑掉，
   `flyway_schema_history` 多 5 行。执行前建议先备份，或在测试库先跑一遍。
3. ⚠️ **已应用的迁移文件一律不改，改动一律新开版本**（校验和会炸，服务直接起不来，
   这个坑在 [`llm-wiki.md`](./llm-wiki.md) §5.15 记着）。
   **分档开场白就是照这条规则办的**：改动没塞进 V6，而是新开了 V8 ——
   这样无论 V6 是否已被应用过都能跑通，也不必跑 `flyway repair`。
   万一确实需要改已应用的脚本，必须先 `flyway repair` 对齐 checksum，否则一律启动失败。
4. **旧的 `docs/dev-ops/mysql/sql/spoken-server-schema.sql` 不再逐表镜像新结构**：
   V4 起以迁移脚本为唯一真源（该文件头部的文件清单已更新并注明这一点），
   想看某张表的确切定义用 `SHOW CREATE TABLE`。
5. V9/V10 不属于纯加列迁移，必须参照 §9.5、§10 备份并暂停写入后执行；新版 mapper 与 V10 要同步部署。
   验证：`SELECT table_name FROM information_schema.tables WHERE table_schema='spoken'`；
   `SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank`。

---

## 8. 评审待定项（想清楚再写代码）

1. **三种登录方式先做哪些**？手机号几乎是基础；微信 / Apple 取决于上架渠道。表结构已通用，不阻塞。
2. **`interview` 的三阶段剧本（V6 种子数据）保留吗**？它是**示例**，删掉即退回单阶段自由聊；
   `free` 场景刻意不配阶段。
3. **要不要 `user_session` / `user_token` 表**？取决于登录态实现 ——
   无状态 JWT 就不需要；要「多端踢下线 / 刷新令牌可撤销」就得加，`user_device.status` 已经预留了「被踢下线」。
4. **`target_scene` 是单值还是多值**？现在 `user_profile` 里是单值（一个主目标）；
   要「同时练面试和旅行」就改成写进 `user_profile_item`。
5. **`training_session` 要不要顺手改名 `conversation_session`**？
   本版**没改**（改名要同步 DAO / PO / mapper XML，DDL 与代码不同步会让服务起不来）。
   llm-wiki 待办里提过这件事，如果要改，最好和 §5 的代码改造放在同一轮。
6. **对话记录的保留期定多久**？影响清理任务与存储成本。
7. **长期水平默认口径**已在 §9.4 定义；上线前仍需用真实样本评估阈值。
   数据库字段存在不代表近期算法已接入，无版本或过期的旧值不得影响开场。

---

## 9. V9 评审修复与代码接入契约（2026-10-12）

DDL：[`V9__data_integrity.sql`](../../spoken-server-app/src/main/resources/db/migration/V9__data_integrity.sql)。
新增 `conversation_finalize_task`、`user_learning_day`、`user_practice_plan_day` 三表；业务表共 19 张（包括仍使用的 turn 表，不含 Flyway）。
本节区分已由数据库强制的约束和后续仓储/worker 必须实现的行为，不能把 DDL 当成完整功能上线。

### 9.1 画像、身份与所有权

- 画像完整值唯一，最多 2312 索引字节，要求 InnoDB 16KB 页。沿用原排序规则；应用先规范化标签，JSON 型值应使用稳定序列化，不能依赖大小写表达两个不同标签。
- `status=rejected` 必须有 `rejected_at`；推断 upsert 不得恢复 rejected 项，只有用户显式恢复才允许。expired 或 `expires_at <= now` 的项不进入提示词。
- `evidence_json` 每项最多保留最近 20 条引用；原料被保留期清理时删除其引用，没有剩余证据的推断项置为 expired，拒绝标记仍保留至账号注销。
- 自述优先指用户偏好与明确自评，不等于系统把用户自评当作客观能力测量。自述标签可使用 item 表的 `user_stated` 来源；不能由推断覆盖。
- 身份唯一键改为 `(provider, provider_scope, provider_uid, active_binding)`；已解绑行的 generated 标记为 NULL，因此可以保留历史并重新绑定。禁止直接恢复已解绑行以绕过绑定验证；重绑重新验证后插入新行，唯一冲突按已被绑定处理。
- 第三方 UID 区分大小写，scope 由服务端按经过验证的 app/issuer 填写；旧行的空 scope 必须在接入相应 provider 前核对回填。phone/email 固定空 scope，不能让客户端指定命名空间。
- 手机先规范化为国际格式，以服务端带密钥 HMAC 作检索值，密文负责展示/短信；密钥不进数据库。设备 id 只用于设备管理，禁止据此推导登录身份。
- 冗余 `user_id` 必须由登录态和所属会话/计划共同核验；reply_to、assessment.message_id 必须属于同一会话，评测只能挂用户消息。私有场景必须 owner 匹配；每个按 id 查询的接口都做所有权检查，自增 id 并不能替代鉴权。
- `scenario_code` 保持全局唯一；自定义场景由服务端分配随机编码，标题可重复，禁止占用官方 free/interview 编码。
- voice/provider 配对读取；个人偏好缺任一值则整对回退到场景配置，不把未知音色和默认 provider 混搭。

### 9.2 消息编号与去重

- `(session_id, seq)` 唯一；`idempotency_key` 是大小写敏感的二进制键。旧数据可空，新仓储必须要求键非空。
- 用户消息键来自稳定请求 id；助手键来自 command id 并附加消息分片号；译文键包含源消息、目标语言与版本。重复 SSE 或 HTTP 重试必须复用原键，不能每次重试造 UUID。
- 同一事务中先锁 `training_session` 行（`SELECT ... FOR UPDATE`），校验用户并按幂等键查询；已存在则返回原消息及原 seq，不分配新号。
- 新消息读取 `next_message_seq`、插入、将计数加 1 后一起提交；数据库回滚会同时回滚序号。V9 已按现有 message 的最大序号初始化计数。
- 回复根据请求/command 保存的明确 message id 写入 `reply_to_message_id`，禁止查询「最后一条」猜归属。旧 turn 链路仍存在查最后一条回填的限制，不能宣称 V9 已修复旧运行链路。

### 9.3 会话收尾与统计幂等

1. CLOSED/ERROR 收口幂等插入 finalize_task，重复事件不改 completed 任务；任务是数据库凭据，不能只靠内存标记。只有有有效用户发言的会话计入练习统计。
2. worker 在短事务内锁任务行，领取 pending、到期 failed 或租约已过期 running；换新的 lease_token，attempts 加一，设置租约。模型生成在事务外进行，长任务需要续租。
3. 提交时按统一顺序锁任务、用户汇总和计划行，核对状态为 running、token 一致且租约未过期；不符合则丢弃结果，不能让超时 worker 更新统计。
4. **一个事务内**完成总结 upsert、画像更新、学习汇总、学习日/计划日写入、计划次数更新，最后置任务 completed。任一步失败全部回滚；重试不能因 summary 已存在就跳过，唯一正确的累计完成凭据是任务 completed。
5. 失败更新仅能由持有当前 token 的 worker 执行，设置 failed、next_retry_at 和错误。重试间隔按指数退避，超过上限报警；completed 状态不可重置为 pending。

均分以有效评测的 `score_sum / assessed_count` 算，不能平均各场平均分。旧用户的 score_sum 为 NULL，首次接入从实际评测重建；旧总结的默认零计数不是「零分证据」。清理过的旧数据无法精确重建时保持未知并记录口径，不伪造统计。

按日统计以用户配置时区下的**会话开始日期**归属，跨午夜不拆场。`user_learning_day(user_id, practice_date)` 只在首次新增日期时增加 practice_days；同日多场只增加当日 session_count。streak 根据按日事实计算，过期的连续天数查询时显示 0，异步乱序补算时重算连续区间。计划 done_days 由 `user_practice_plan_day` 首次插入去重；done_count 每个已累计会话增加一次。时区默认 Asia/Shanghai，变更时需要从会话重建日期事实，不能只改设置后继续累计。

### 9.4 近期长期水平默认口径

2026-10-12 首句链路已通过 V11 接入：当前匿名入口从同设备未归属账号的历史重建近期信号，将档位与首句决策持久化为会话快照；账号统计 worker 仍不在本轮范围。实现边界、31 项测试和手机结果见[首句实现与验证记录](opening-optimization-verification-2026-10-12.md)。

- 仅使用最近 30 天内、最近 10 场有有效信号且完成收尾的会话；无文本且无评测的轮次不产生信号。
- 每场以 LOW/MID/HIGH 有效信号多数票决定档位；并列时取较低档。将 LOW/MID/HIGH 编码为 0/1/2，按会话等权平均，避免长会话独占权重；均值 `<0.5` 为 LOW，`>=1.5` 为 HIGH，其余 MID。
- 少于 3 场有效样本则不设长期水平；超过窗口的历史不得继续作为开场依据。自评优先，其次是有效长期水平，最后场景默认。
- 总结保存三档信号次数、聚合证据和 `level_algorithm_version`；统计保存窗口起止、样本场数和版本。初始实现版本命名 `recent10_30d_v1`，升级算法需重算，不能混用没有版本的历史结果。
- 窗口在建会话前检查是否过期；累计均分只用于统计，不用于替代档位信号。重算是持久化读模型，运行时的逐轮滞后控制仍在内存中。

### 9.5 保留期、注销与迁移验证

- V9 阶段消息和总结 created_at 必填；V10 最终统一为数据库生成的 DATETIME。旧空值分别优先补会话开始/结束时间，连会话时间都缺失才使用迁移时间，该兜底值不能用于精确历史分析。
- V9 为消息、总结、评测补保留期索引，为待练条目补 `(user_id, status, due_date)`。登录日志已有时间索引；历史 NULL 时间需人工审计，不允许因无法排序而永久遗漏。
- 对话保留期由产品配置，未确定之前不启用自动清理。按完整会话清理，先确认没有 pending/running/failed 收尾任务；先清关联证据，再评测、译文/回复、消息与旧回合、总结、completed 任务，最后会话。对象存储删除需可重试记录，不能只删数据库 key 后丢失删除目标。
- 注销先禁用账号、撤销登录态、停止会话并阻止新收尾提交，再清设备、身份密文/哈希、画像、统计、按日事实、计划与条目及私有剧本。必要审计记录去标识化后按保留期清理，软删本身不等于删除用户数据。
- **V9 有 DROP INDEX、唯一约束与非空改造，并非 V4~V8 那种纯加列迁移**。MySQL DDL 非事务回滚；执行前检查重复消息 seq、非法枚举/置信度/时长，并备份。发生失败先核对实际结构，不能直接 repair 后跳过未执行语句。
- 验证脚本：[`verify-data-model.ps1`](../dev-ops/mysql/verify-data-model.ps1)。只创建随机 `spoken_schema_verify_*` 临时库，分别测试空库和带 V8 历史数据升级到 V10，结束后删除测试库；不迁移业务库、不重启服务。需要测试账号拥有建库权限。
- V1~V10 迁移和验证脚本必须纳入版本控制；V1~V8 保持原文。DDL 执行不代表 §5 的登录、消息、画像和 worker 功能已实现。

## 10. 全表审计字段统一为 DATETIME（V10）

**开发库执行状态（2026-10-12）**：ADB 联调启动前已备份 `spoken`，核对并修复 V5 历史校验值后，服务成功执行 V8～V10。已只读确认 19 张业务表的 38 个审计字段全部符合下述定义。详情见 [ADB 联调记录](adb-smoke-test-2026-10-12.md)。

按本轮要求，19 张业务表（含当前使用的 conversation_turn）最终全部包含：

```sql
`created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
`updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间'
```

迁移：[`V10__audit_datetime.sql`](../../spoken-server-app/src/main/resources/db/migration/V10__audit_datetime.sql)。Flyway 的 schema_history 由框架维护，不纳入业务表改造。

- 历史毫秒按 Asia/Shanghai 转成秒精度 DATETIME，舍弃不足一秒的部分。created_at 缺失时优先取首次业务时间；updated_at 缺失则取 created_at，未知时间最后用迁移时间兜底。首次业务时间缺失时不能把兜底当成真实历史事件。
- 每张表先建立影子日期列、转换原数据，再替换旧列；所有引用 created_at 的组合索引都按原名重建，去重和外部关联 id 不变。禁止直接对 BIGINT 执行 MODIFY DATETIME 让 MySQL 猜格式。
- DATETIME 没有时区信息，约定表示上海本地时间。迁移强制 session time_zone=+08:00，完成后恢复；应用 JDBC 强制连接时区 Asia/Shanghai，PO 使用 LocalDateTime，领域和已有 API 的 epoch 时间由 DatabaseTime 显式转换。
- 现有三张表的 mapper INSERT 不再传 created_at/updated_at，使用数据库默认值；业务 UPDATE 不显式覆盖审计字段，数据库自动维护 updated_at。没有实际字段变化的 UPDATE 不要求刷新更新时间。
- `started_at`、`ended_at`、`deleted_at`、租约等业务字段和 `latency_ms` 等耗时不属于本轮 created_at/updated_at 统一范围，保留既有类型和接口语义。
- 未来新表必须直接采用上述两列；迁移旧脚本只描述历史结构，不回改 V1~V9。
- 时间保留期查询用 DATETIME 边界（例如 `created_at < CURRENT_TIMESTAMP - INTERVAL 90 DAY`），不能再用 epoch 数字比较。worker 的任务登记/更新审计时间也交给数据库，next_retry_at、lease_until 仍按 epoch 毫秒处理。
- V10 涉及列替换与索引重建，执行前暂停业务写入并备份，部署时先迁移再启用新 mapper；不能让旧版 Long 映射读取新 DATETIME。

验证覆盖：38 个审计列的类型/非空/默认值、19 个自动更新定义、原索引保留、历史毫秒转换、数据库默认插入和实际 MyBatis 会话/回合/评测读写。MySQL 8.0.32 已通过空库安装及 V8 历史升级测试。

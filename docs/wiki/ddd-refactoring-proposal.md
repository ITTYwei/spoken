# 口语陪练领域重构与迁移说明

各领域和子领域的职责、新需求归属及协作数据流见 [领域职责说明](domain-responsibilities-2026-10-16.md)。

本文件描述 2026-10-16 这次重构后的代码归属，替代此前按旧目录划分的方案。参考本地 [xfg-ddd-skills](../../.skill-sources/xfg-ddd-skills/SKILL.md) 的六边形依赖与目录规范；领域边界依据实际业务决策、状态生命周期与一致性要求确定。

## 四色建模与领域边界

四色用于寻找角色、事实和聚合边界，不把四种颜色直接映射成四个领域，也不以数据库表或技术组件作为领域名称。

| 颜色 / 模型 | 本项目中的含义 | 边界判断 |
| --- | --- | --- |
| 绿色：参与方、地点、物品 | 用户账户、伙伴形象、可学习的教材资源 | 谁参与陪练、使用什么资源；账户认证归 Identity，内容定义归 Content |
| 黄色：角色 | 学习者、口语伙伴、评测者 | 同一用户作为学习者有能力进展；伙伴负责上下文与记忆；评测者产出证据 |
| 蓝色：描述 | 教材/课文定义、场景内容、水平含义、教学规则 | 定义与一次发生的活动分开；当前任务不能反向改写教材定义 |
| 粉色：时标 / 事件事实 | 陪练会话、用户回合、任务执行、实际播报完成、评测记录、学习证据、明确记忆及遗忘边界 | 根据发生时间、归属、完成条件及事务要求划分聚合；模型生成结束与媒体播放结束是不同事实 |

这些是建模含义，不为每种角色机械创建一个实体类。当前实现使用已有聚合、状态对象和业务策略承载规则。

| 领域根包 | 负责的真实决策 / 生命周期 | 当前核心实现 |
| --- | --- | --- |
| `identity` | 登录身份、账号设置、明确用户资料和已保存伙伴选择 | AccountService、HomeService、AccountContext |
| `content` | 教材、课程与场景定义、容量和有效编号 | TextbookService、场景与教材仓储 |
| `coaching` | 会话状态、教学目标、练习模式、已播任务、用户回合、会话档案 | ConversationSessionAggregate、TeachingStateAggregate、PracticeService、ConversationHistoryService |
| `assessment` | 发音评分与独立表达观察的判定、结果有效性 | pronunciation 子域、expression/AbilityEvidenceService |
| `learning` | 证据接纳、稳定等级、手动难度、能力描述 | LearnerProgressService、LearnerLevelService、AbilityDescriptionPolicy |
| `companion` | 伙伴上下文、近期历史选择、长期记忆召回/确认/遗忘、对话入口 | ChatContextService、providers/reducer、LongTermMemoryService、IChatService |

`shared` 只保留已有公共技术配置端口，不作为承载业务逻辑的新领域。

### Coaching 内的教学边界（2026-10-16）

`coaching` 下分为 `conversation`、`teaching`、`practice`、`history`。
`conversation` 管理会话归属、生命周期、用户轮次和有效回复；新增的 `teaching` 持有教学状态、待播计划、跟读目标、纠音次数和教学策略。
`practice` 管理课程模式与继续邀请；`history` 提供持久会话与轮次的查询视图。

`ConversationSessionAggregate` 不再持有 `TeachingStateAggregate` 或待播教学计划。
`TeachingService` 按业务 sessionId 管理独立教学运行态，关闭会话时释放。
会话主体统一使用 `bindSubject/subject`，供历史、课程、工具和教学证据共享，不再以教学命名。

教学编排 `TeachingPreparation` 与跟读按钮用例 `GuidedReadingCase` 位于 `cases/teaching`。
Case 在同一业务会话锁内协调回复失效、教学计划失效与媒体完成提交；归档凭据、对话文本和教学文本仍由 `ReplyArchiveCase` 在同一事务内写入。
详细数据流和拆分依据见 [教学子域拆分](teaching-subdomain-split-2026-10-16.md)。

## 依赖与目录

保留 `spoken-server-case` 和 `cases` 包，不改名为 application。

```text
Trigger -- simple, single-domain operation --> Domain
Trigger -- cross-domain workflow ----------> Case --> Domain
Infrastructure ----------------------------> Domain ports / repositories
App ---------------------------------------> component assembly

Domain
  identity / content / coaching / assessment / learning / companion
    adapter/port, adapter/repository
    model/aggregate, model/entity, model/valobj
    service
Case
  conversation/impl             # 语音、评测、伙伴回复总装
  conversation/runtime          # 媒体资源、回复投递和完成回执
  conversation/translation      # 归属、已保存字幕、按需翻译
  teaching                      # 教学准备、上下文组合和情境跟读按钮
  overview                      # 首页快照、账号资料 + 学习描述 + 明确事实
  practice                      # 课程切换、Agent 动作与播放完成协调
Infrastructure
  adapter/port                  # 实现领域出站端口
  adapter/repository            # 实现领域仓储
  dao/po                        # 数据库操作与持久对象
  gateway/agent                 # ADK、Spring AI、MCP、SDK 事件转换
  gateway/dto                   # 供应商协议对象
  config/agent                  # 装配配置及 Spring 配置绑定
  redis                         # Redis 操作
```

Domain 不依赖 Case、Infrastructure、API 或模型 SDK。Case 不依赖 Infrastructure。Infrastructure 不引用 Case、Trigger 或 API。

简单教材查询和历史查询直接进入对应 Domain；切换课程仍经 Case 校验主体、更新练习状态并协调媒体。创建会话中的可信设备索引和伙伴选择已移至 SessionCreationCase。语音主链路继续由 ConversationCaseImpl 编排。

媒体回执协调 PracticePlaybackCase 仅依赖领域状态、仓储和事件端口，不再回调 PracticeCase 或媒体运行服务，从而解除循环依赖。

## Case、Trigger 与无用接口清理

按“有编排进入 Case，简单调用直接进入 Domain”清理：

- 删除 catalog 的 ICatalogCase / CatalogCaseImpl，目录入口直接调用形象端口和场景仓储，HTTP 字段映射留在 Trigger 的 CatalogResponseAssembler。
- 删除 LearnerLevelCase，档位入口直接调用 LearnerProgressService。保存与回读的事务放在领域服务 select，保留异常整体回滚。
- 删除 PracticeCase 中教材列表、轻量目录、Agent 目录、课程详情四个空转发方法；Controller、Agent 动作和回复准备直接查询 TextbookService，课程切换继续经过 PracticeCase。
- 删除 IPronunciationCase / PronunciationCaseImpl 及两个独立发音 Controller。语音主链路继续编排评测；仅供其使用的 PronunciationAssembler 移入 conversation/impl，去掉历史摘要、语言和音素工具转换方法。
- 删除未被客户端使用的 /say HTTP 入口及其请求 DTO、Case 声明和实现。内部脚本播放端口仍支持开场、教学示范和 TTS，不影响这些正式流程。
- 删除五个专用 DTO：AssessmentRecordResponseDTO、LanguagesResponseDTO、PhonemesResponseDTO、TranscriptResponseDTO、SayRequestDTO；删除 Trigger 中仅占位的 http、job、listener package-info 文件。
- 首页需要组合账号已保存选择、形象和音色，保留这段编排并移入 HomeQueryCase。删除资料服务中只为旧首页提供的 profile 转发和只被测试调用的 ability 静态包装。

本轮删除的公开接口如下，不保留兼容转发：

| 已删除接口 | 当前处理 |
| --- | --- |
| POST /api/v1/sessions/{sessionId}/pronunciation | 跟读随 /voice 主链路评测，结果在 assessment 字段返回 |
| GET /api/v1/sessions/{sessionId}/pronunciation | 本轮不提供独立评测记录查询；会话聊天记录仍走 chat-history |
| POST /api/v1/sessions/{sessionId}/say | 内部媒体能力保留，对外继续使用 /speak 或 /voice |
| GET /api/v1/pronunciation/languages、/health | 移除独立调试入口 |
| POST /api/v1/pronunciation/speech2text、/phonemes、/tts | 移除独立工具入口 |

Android 当前声明仍使用的 catalog、home、英语档位、speak、voice、events、practice、翻译与聊天历史接口继续保留。本轮未修改 Android；SpokenPracticeTools 由配置注册并通过模型反射调用，继续保留，不能按普通 Java 显式引用数量删除。

## Agent 和流式对话

Agent 的人格、上下文、召回与遗忘仍在 Companion。技术装配配置、Runner、SDK 插件、MCP 连接和事件转换放在 Infrastructure。独立表达评测的业务指令和有效性规则归 Assessment，模型网关只执行隔离的观察请求。

- IAgentExecutionPort 只暴露业务输入及 Reactor 文本流，不暴露 ADK Event、Runner、RxJava 或 Spring AI Prompt。
- 已有 ADK / Spring AI 调用继续复用；事件到文本的转换集中在 AgentTextStream，没有新增一套模型 HTTP/SSE 解析器。
- 每轮动态策略和已召回历史使用临时 ADK 会话，采用 usingWhen 统一管理创建、正常结束、错误和取消时的清理；该路径不再以 blockingGet 包装异步 API。
- 可信工具上下文仅由服务器注册，结束后撤销；翻译与后台观察维持禁用业务工具的隔离约束。
- 查询伙伴只返回 AgentProfileVO，不向 Domain 或 Trigger 暴露包含供应商地址、密钥和装配模块的配置对象。

Agent 网关内部同步方法仍供装配验证和独立联调使用，但早期公开的通用 Agent HTTP 调试入口已删除。对外统一经过口语陪练会话用例；未改写供应商数字人事件协议转换。

## 持久会话与聊天记录

历史查询使用已有 `training_session`、`conversation_turn`，不依赖活跃媒体资源。旧 `/sessions/{sessionId}/turns` 已删除；连同原 Case/运行服务转发、Assembler 回合转换和无分页的仓储/DAO/Mapper 查询一并清除。统一认证仍按数据库中的账号归属拦截，历史读取直接进入 Coaching History 领域。

新增接口均以当前 Bearer 登录账号为查询主体，不接收客户端 userId：

| 接口 | 用途 / 翻页 |
| --- | --- |
| `GET /api/v1/chat-history/sessions?limit=20` | 会话列表，按 startedAt、sessionId 倒序；下一页传 beforeStartedAt 和 beforeSessionId |
| `GET /api/v1/chat-history/sessions/{sessionId}` | 已持久化的会话详情，包含结束时间 |
| `GET /api/v1/chat-history/sessions/{sessionId}/turns?afterTurnId=0&limit=20` | 按持久 turnId 升序返回聊天记录；下一页使用 nextCursor |

分页响应为 `items` 和 `nextCursor`，每页限制为 1~100；最后一页 nextCursor 为空。不存在与非本人会话统一返回 session_not_found。

正常创建会话后，服务端立即建立一次驱动事件消费。SSE 客户端读取共享热流，不重复执行回合落库；客户端未订阅或断开时，后台仍处理实际媒体完成回执。即时字幕不重放，客户端可通过历史接口恢复已完成文本。

回复开始时固定关联 turnId，完成时不再读取“最后一条回合”来猜归属；重复完成回执不重复写入。主动开场和跟读示范若没有对应用户回合，保存独立助手回合，userText 为空，不覆盖上一条真实用户答案。

档案插入失败不会发布内存索引，媒体句柄由 Case 清理；终态 ended_at 使用首次结束时间，重复关闭不反复改变档案时间。

当前系统没有管理后台角色模型。上述接口提供用户本人查询，领域查询契约支持后续由经过授权的后台用例传入目标账号。后台跨用户查询仍需单独实现角色授权和访问审计。历史匿名会话没有可信 user_id，不自动猜测或归并到账号。

聊天档案当前保存用户文本和已完成助手回复；打断中尚未完成的字幕片段没有持久投递状态，不能宣称可完整恢复。原有音频归档字段仍预留，不生成不存在的录音地址。上述范围不影响本次关闭后查询和回合归属修复。

## 旧接口清理与 Android 同步

已删除早期 AgentServiceController、IAgentService、对应的五个 DTO 及 Response 外壳，不保留别名或重定向：

| 已删除路由 / 声明 | 当前调用方式 |
| --- | --- |
| GET /api/v1/query_ai_agent_config_list | 使用首页 /home 与 /catalog/* 选择伙伴 |
| GET、POST /api/v1/create_session | POST /api/v1/sessions，由可信身份和会话用例创建 |
| POST /api/v1/chat、/api/v1/chat_stream | 会话 /voice 或 /speak 输入，/events 接收字幕和状态；不是旧 SSE 的同协议替换 |
| GET /api/v1/sessions/{sessionId}/turns | /api/v1/chat-history/sessions/{sessionId}/turns，使用 items + nextCursor |
| Android 的 POST /api/v1/training/translate | 删除已失效的声明与 TranslateRequest/TranslateResponse，继续使用所属回合 /translation 与开场 /opening/translation |

早期 Nginx Agent 示例页和说明已停用并标明迁移入口，不再发送旧请求。内部 ADK 会话生命周期方法仍服务正式模型链路和独立联调，不属于已删除的公开 HTTP 接口。

同级 Android 项目已同步：

- Retrofit 增加会话列表、档案详情和回合分页方法；SessionHistoryCursor 与 HistoryPage 明确区分两种游标。
- 设置页新增“聊天记录”，点击历史会话可查看开始/结束时间与用户、伙伴文本；支持刷新、继续加载、空状态和失败重试。
- 主动开场允许只有伙伴文本，不补造用户消息；没有音频归档时不展示虚假的播放入口。
- ChatHistoryStore 捕获登录快照，切号和退出时取消查询并清空状态；快速切换会话会废弃上一场详情的迟到结果。使用现有 ApiFactory 统一添加 Bearer，不增加客户端 userId 参数。
- 历史读取不恢复媒体会话；保持现有跟读、发音评测、按需翻译、WebRTC 和字幕事件协议。

这是删除旧接口的升级，服务端与 Android 需一起发布。旧路径不再受支持，不能依赖客户端降级回旧入口。Android APK 已构建；未安装设备或进行真机联调。

## 数据库迁移

新增 `V18__conversation_history.sql`，不修改 V1~V17 的字节和校验和：

1. 历史未知 started_at 显式标为 0，并设为 NOT NULL DEFAULT 0，让列表排序与游标契约一致。
2. 增加会话 `(user_id, started_at, id)` 和回合 `(session_id, id)` 查询索引。
3. user_text、assistant_text 扩为 MEDIUMTEXT，避免长回复碰到原 4000 字符限制。

包与领域改名不要求物理表改名。turnId、评测关联与已有教学证据保持兼容。V7/V9 的 conversation_message 本次没有启用，也没有双写；它不能被当作当前聊天事实来源。长期记忆与 learner_profile 的已有数据库遗忘边界保持现有事务行为，本次未修改其物理模式。

部署时需备份并在独立 MySQL 环境验证 V18 与索引耗时，再部署新版本；Flyway 启动会应用新增迁移。MySQL DDL 不能整体回滚，发生应用回滚时保留新增索引和扩容字段，不把长文本强行缩回 VARCHAR。需要回滚应用时使用与 V18 扩展模式兼容的旧版本。

这次工作只提供迁移文件，没有连接或修改已部署数据库。

## 验证与维护

ArchitectureBoundaryTest 检查核心层依赖方向，并使用 MyBatis 实际解析全部 Mapper，核验包迁移后的类型引用。历史接口和服务有账号隔离、关闭后读取、稳定游标与页容量验证。媒体归档有连续用户回合绑定、重复回执、无 SSE 客户端仍落库验证；原有语音、教学、课程、学习、记忆、工具隔离与模型流测试一并回归。

当前 app 使用旧 Surefire 2.6；本地 JDK 21 运行回归时需使用 forkMode=once 并通过 argLine 预加载 Mockito Java agent，避免旧隔离类加载器无法看到 java.sql/java.net.http 模块，以及动态 attach 管道问题。默认 skipTests 配置未改变；执行测试必须显式传 -DskipTests=false。在线供应商与真实数据库测试需单独配置环境，不能把离线回归通过等同于部署验证。

新增与实质重构的核心类采用统一注释：功能 → 体系架构 / 运行过程的 pre ASCII 流程图 → 具体输入输出案例 → 设计要点 ul 列表。

实际验证结果（2026-10-16）：8 个 Maven 模块编译通过，39 个测试类、217 项本地回归全部通过，失败 0、错误 0、跳过 0。ApiRouteContractTest 扫描实际 Controller，检查旧入口删除、正式会话/历史/翻译路径保留。ReadEndpointHttpTest 通过真实认证拦截器与 Spring 事务代理验证目录和首页字段、当前账号归属、档位保存提交和回读失败回滚。已执行 clean 构建以清除移动和删除类留下的旧 class。Android core-net 的 4 个测试类、24 项测试通过，其中新增 6 项历史查询网络回归，覆盖游标、关闭后读取、失败续页与刷新重试、账号隔离和快速切换详情；app:assembleDebug 通过。两个仓库 git diff --check 通过，V1~V17 无 Git 差异。未执行真实数据库迁移、线上供应商联调或真机验证。

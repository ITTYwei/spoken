# 数字人口语训练项目 — LLM Wiki

> 开发提交约定：用户已授权每次修改完成并通过必要验证后，自动暂存本次相关代码与文档并提交到本地 Git；提交标题和说明统一使用中文，保留其他未提交改动。远程推送需另行授权。

> 开发类型约定：新增业务状态采用所属领域的枚举，逐项用中文解释触发条件、处理方式和边界，避免用魔法字符串进行业务判断；协议和持久化在边界显式转换稳定码。

> 2026-10-14 英语知识分流：明确词义、语法、翻译、表达和发音方法问题优先直接解答；新增 ENGLISH_QUESTION / EXPLAIN 枚举，不强制跟读或纠音，不执行业务工具及设置兜底。问句原目标完整复述仍是跟读。64 项检查通过，含两条当前模型实测，详见 [分流说明](english-question-routing-2026-10-14.md)。

> 2026-10-14 手机连接改为局域网：Android 默认地址及手机保存地址改为 http://192.168.20.41:8091，新包已安装；手机 Wi-Fi 直连健康接口返回 200/UP，adb reverse 为空。切换来源后可能需重新登录，完整新媒体会话待用户体验，见 [局域网连接记录](phone-lan-access-2026-10-14.md)。

> 2026-10-14 教学结果类型化：TeachingDecisionVO.outcome 改为 TeachingOutcomeEnum，九种结果逐项说明；策略、提示词及 Case 使用枚举，JSON 和数据库保持原文本码。见 [教学结果说明](teaching-outcome-enum-2026-10-14.md)。

> 2026-10-14 陪练准入：自有提示词加入零评判安全空间和相关追问准则，跟读保留可信发音指导与唯一任务；清除模型输出中的对错视觉符号，具体可信错词不再被整句高分掩盖。见 [两层准入说明](coaching-admission-principles-2026-10-14.md)。

> 2026-10-14 对话流转说明：按当前源码整理手机音频、识别、评测、教学任务、Agent 上下文与工具、数字人播报及完成回执，补充普通聊天、问句跟读、继续课程和长期目标案例。关键业务入口已补充中文流程注释，见 [用户对话数据流](conversation-data-flow-2026-10-14.md)。

> 2026-10-14 问句跟读与译意修复：引号中目标不再误提交 QUESTION，练习问句禁止当作用户提问；L1/L2 英文后解释，过滤器保留明确译意且不覆盖英文待答问题。上次话题改取最近实际发言会话的已完成非跟读记录。89 项回归（含 L1/L2 真实模型）与 23 项数据库相关检查通过，需 IDEA 重启并新建对话。详见 [修复记录](reading-question-and-recent-topic-2026-10-14.md)。

> 2026-10-14 ToolContext 异常修复：后台能力观察仅清空回调仍继承共享模型默认工具，误调用时报缺少上下文。已请求级禁止后台观察、翻译及跟读的内部工具执行，不影响可信对话工具；32 项检查通过，含真实模型三轮业务工具回归，IDEA 重启加载。详见 [后台工具隔离](background-tool-isolation-2026-10-14.md)。

> 2026-10-14 自有 LLM 提示词中文化：教练、六档规则、课程、历史边界、后台观察、字幕翻译及八个业务工具说明均改为中文；英文教材与协议字段保留。遵照用户要求撤销框架身份翻译，不改 ADK 和脚手架示例。100 项检查通过，含真实模型三轮工具调用，IDEA 重启加载。见 [变更记录](chinese-llm-prompts-2026-10-14.md)。

> 2026-10-14 继续选项体验：两个按钮绑定询问气泡，只在真实播报完成后显示在该对话下方；点击提交收起。后端继续/自由两种选择均播报对应台词，操作重试不重播，35 项定向检查通过，Android 构建更新。详见 [对话选项记录](continue-choice-dialogue-2026-10-14.md)。

> 2026-10-14 最新用户约定：普通聊天由 Agent 静默维护明确兴趣/长期目标，不向用户播报保存或未保存回执，后台失败不打断教学。已修复 `practiceInput=false` 时“I like...”触发强制设置提示的问题，61 项相关校验通过；原有跟读禁用业务工具保留。代码需 IDEA 重启加载，手机仍连接 8091。详见 [跟读与静默维护](reading-tool-isolation-2026-10-14.md)。

> 2026-10-14 跟读隔离：用户原句“I like reading books”日志没有工具调用，但原装配仍提供业务工具。已增加请求级 toolChoice=none、全部业务能力拒绝练习输入，以及跟读跳过偏好/切换兜底；59 项相关校验通过。手机保持连接 IDEA 8091，重启 IDEA 后端加载源码修改。见 [跟读隔离记录](reading-tool-isolation-2026-10-14.md)。

> 2026-10-14 对话修复：真机课程语音回合识别完成后报 `current_turn_context_too_large`，旧 6000 字节预算装不下完整必要上下文。默认/开发配置调整为 12000，增加六级真实教学策略回归，59 项相关检查通过。已更新本机 8092；重启前会话须重新建立，详见 [修复记录](context-budget-fix-2026-10-14.md)。

> 2026-10-14 最新：用户授权依次继续，数字人主界面、伙伴设置、三组本地文本课、继续课程与 Agent 个人管理工具第 0–5 步实现完成。现有七模块/ADK/Spring AI 工具装配沿用；业务状态和可信账号由后端注入，模型不持有业务身份。开发库新增 V16 最近课，手机测试使用本机 8092 新实例，8091 原实例保留。构建、领域/数据库/真实模型工具及真机按钮流程已验证；完整自然语音操作和声学质量仍需体验验收。以 [最终交付与验证边界](avatar-practice-delivery-2026-10-14.md) 为准，以下早期条目是历史记录。

> 2026-10-14 数字人主界面第 1 步已交付 Android 可体验版本：首次确认后直达数字人，静默预连接、主动开始、延迟录音权限、60 秒释放和左上设置入口已接通。修复数字 `expiresAt` 导致登录无法恢复的问题，11 项网络模块检查通过；真机完成二次语音协商与 AEC 回调验证，声学消回声质量仍需体验验收。详细改动、性能和限制见 [第 1 步交付记录](avatar-practice-stage-one-2026-10-14.md)。第 2 步等待用户确认。

> 2026-10-14 数字人主界面与课程陪练第 0 步完成：BandReady 源码已固定下载到 `D:\develop\code\github\ielts-prep`，现有脚手架通过当前模型完成同步/流式只读工具调用，14 项定向检查通过。媒体静默预连接完成源码核查，二次协商及 AEC 待第 1 步真机验证；当前生产接口与手机页面未修改。来源、限制和复现见 [第 0 步验证记录](avatar-practice-stage-zero-2026-10-14.md)。逐步实施，用户确认后再进入下一步。

> 2026-10-13 第一阶段后端接口已接入：账户密码注册/登录、持久Bearer令牌、退出/改密撤销、首页音色试听与选择、“我的”资料/偏好、近期能力描述及按需翻译。会话归属写入`training_session.user_id`，教学身份捕获为`user:<id>`，所有会话子接口统一校验账号归属。客户端接入和上线约束见[`phase-one-api.md`](./phase-one-api.md)。新增迁移V15；业务库和运行服务尚未应用。Android页面本次未改。

> **这份文件是给「AI / 新人」衔接用的项目记忆。**
> 换工作目录、开新会话时，先让 AI 读本文件，即可直接衔接，不必重新摸一遍。
> 最后更新：2026-10-10。维护原则：每次有架构级改动，同步更新本文件。
>
> 📄 **姊妹文档：[`product-design.md`](./product-design.md)（产品设计逻辑）** ——
> 本页讲「代码怎么写的」（架构、坑、环境、迁移），那一页讲「产品为什么长这样」
> （人格契约、分级语言策略、评测可见性边界、交互与降级语义）。
> **要改产品行为（追问策略、档位规则、评测呈现），先读那一页。**
>
> 🗄️ **数据模型：[`user-data-model.md`](./user-data-model.md)（账号 / 画像 / 对话策划 / 对话记录）** ——
> V4~V8 新增 13 张表与 3 张改造的评审说明。**含一个破坏性契约变更：`turnId` → `messageId`**，
> V9 修复画像/身份唯一约束，补齐消息幂等、收尾任务与按日统计；当前业务代码仍使用旧 turn 链路，见该文档 §9。
> V10 将全部业务表的 created_at/updated_at 统一为 DATETIME 默认创建/自动更新时间，PO/mapper 已同步，见该文档 §10。
> 以及「从设备升级到用户」带来的连锁代码改动清单。

---

## 0. 30 秒速览

- 这是一个「**英语口语陪练**」产品：用户对手机说英语，数字人（AI 形象）用英语陪练。
- 三端链路：**Android App（Kotlin/Compose）→ Java 后端 → OpenTalking（GPU 数字人引擎）**。
- 本目录 `spoken-server` = **Java 后端**（当前开发主战场），包名 `cn.adinall.ai`，Spring Boot 3.4.3 + Google ADK agent 脚手架 + 迁入的数字人对话后端。
- 后端沿现有 Agent 脚手架实现教学上下文、课程与个人管理工具；STT、TTS 与画面继续走既有媒体能力。普通对话流式返回，明确业务动作等待工具真实结果再播报。
- 另有第四条链路：**跟读发音评分 → OpenPronounce**（GPU 机 docker，`172.18.0.102:8070`）。2026-10-10 已对接，见 §2.7。

---

## 1. 代码位置地图（务必先看）

| 位置 | 内容 | 状态 |
|---|---|---|
| `D:\develop\code\project\jugao\spoken-server`（本目录） | Java 后端（`cn.adinall.ai`） | **当前主战场** |
| `D:\develop\code\project\jugao\android` | Android App（Kotlin/Compose） | 2026-10-10 迁到本机，与后端同级目录；评测链路的 App 侧改动在这里 |
| `C:\Users\16639\Documents\Codex\2026-09-23\nm-172-18-0-102-opentalking\spoken-ai` | 迁移来源：原 Android App + 原 Java 后端（`com.spokenai`） | 旧 Java 后端基本被 spoken-server 取代；Android 已迁到上一行 |
| GPU 机 `172.18.0.102` 上 `/home/nm/develop/code/` | OpenTalking / SoulX-FlashHead / omnirt（有本地补丁） | 生产运行中，见 §4 |
| GPU 机 `/home/nm/develop/code/OpenPronounce` | OpenPronounce 源码（有 git，MIT） | 上游原件，未改 |
| GPU 机 `/home/nm/develop/code/openpronounce-deploy` | OpenPronounce 部署（docker-compose + Dockerfile + 部署步骤.md + 模型缓存） | 生产运行中，见 §2.7 |

**重要**：Android App 代码在**隔壁目录** `D:\develop\code\project\jugao\android`（不在本仓库）。改 App 去那边。

---

## 2. 架构与关键概念

### 2.1 三端职责
- **App**：录音（按住说话 / 实时对话两种模式）、WebRTC 下行收画面+声音、上行音频、SSE 收字幕事件。
- **Java 后端（本目录）**：会话生命周期、目录、WebRTC 信令中转、语音上行转 STT、脚本轨下发、SSE 事件流。
- **OpenTalking（GPU 机）**：数字人画面渲染（FlashHead）+ 音频驱动（FlashTalk）+ STT（dashscope）+ TTS（edge-tts）+ LLM。

### 2.2 双轨制（最核心的概念）
| 轨道 | 端点 | 语义 |
|---|---|---|
| **生成轨** | `POST /sessions/{id}/speak` | `text` = 用户说的话 → OpenTalking 的 LLM 生成回复（慢，首块 ~6.9s） |
| **脚本轨** | `POST /sessions/{id}/say` | `text` = 数字人要说的话 → 直通 TTS（快，首块 ~1s），**不经过 LLM** |

- **系统提示词只作用于生成轨**（LLM）；**`tts_voice` 作用于 TTS 阶段，两条轨都受它影响**——这是"强制英语"唯一能同时管住两条轨的旋钮。
- 开场白、题目、判分反馈等**内容已定**的，必须走 `/say`（脚本轨），不要走 `/speak`。
- `/say` 是当初为了「外部（agent）驱动数字人念文本」预留的位子——**后续 agent 接管 LLM 后，agent 出文本 → `/say` 念**。

### 2.3 会话与幂等
- 会话是**进程内**的（重启即丢，客户端重建会话，这是历史行为）。
- `POST /sessions/{id}/opening`（开场白）幂等两层：
  1. 聚合内 `openingSpoken` 用 `synchronized` 原子跃迁（业务不变式）；
  2. `commandId = "{sessionId}:opening"` 透传上游（网络重放）。
- `openingSpoken` 不落库；`scenario_id` 落库（存在 `training_session` 表，见 §5 命名坑）。

### 2.4 语音上行与回声消除（重点）
- **上行音频走 WebRTC，不是 HTTP**：Android 端把麦克风音轨挂到 PeerConnection（`SEND_RECV`），走 WebRTC 的 **AEC3 软件回声消除**。
- **为什么必须走 WebRTC**：平台硬件 AEC（`AcousticEchoCanceler`）在本机要拿 29.4dB 灵敏度去换（底噪 -56.8 → -86.2，麦克风基本聋了），用不了。WebRTC 的 AEC3 是软件实现、参考信号取自它自己的播放端，**不依赖 Android 音频模式**，所以"扬声器大声播 + 回声被消掉"能同时成立。
- **识别用的音频必须从服务端 WebRTC 上行缓冲取**（`take_mic_pcm()`），不能再用客户端 HTTP 上传的那份裸 PCM（那份没 AEC，数字人说话会被录进去）。客户端现在只发一个"我说完了"的空 body。
- 服务端 `adapter.py` 里 `pc.on("track")` 接住上行，重采样成 16k/mono/s16le，`take_mic_pcm()` 供识别取用。

### 2.5 音频路由（Android）
- WebRTC 默认 `USAGE_VOICE_COMMUNICATION` → 系统当打电话处理 → 路由到**听筒**（声音很小）。已改成 `USAGE_MEDIA` → 走扬声器 + 受媒体音量控制。
- 见 `SpokenRtcClient.kt` 的 `setAudioAttributes(USAGE_MEDIA)` + 关掉硬件 AEC/NS。

### 2.6 单 GPU 槽约束（重要架构约束）
- OpenTalking 是**单 GPU 槽**：`task_consumer.py` 里 `_flashtalk_slot_lock` 锁**整个会话生命周期**，一次只能一个会话跑。
- 表现：会话多时排队（`session_queued` / HTTP 409），App 的 offer 会连续失败。
- 已知坑：**排队中被 DELETE 的会话会变僵尸**（`unknown session ... for cmd close`），既不取消也不释放队列计数，只能重启 OpenTalking 清。
- 相关补丁：`flashtalk_attach_timeout_sec=120`（弃置会话释放槽）、App 侧等会话超时 600s。

### 2.7 跟读发音评测（OpenPronounce，2026-10-10 新增）
- **它是什么**：MIT 许可的开源「音素级发音评分」服务。给一段录音 + 期望文本，回分数、错词（含逐音素 IPA）、转写、音高/能量曲线。CPU/GPU 都能跑，**无需 API key**。
- **部署**：GPU 机 docker 容器 `openpronounce`（镜像 `openpronounce:local`），宿主机 **8070** → 容器 8000。
  - `OPENPRONOUNCE_DEVICE=cuda`（RTX 5090）→ 实测 **~0.2 秒/句**；退回 cpu 会慢一到两个数量级。
  - `OPENPRONOUNCE_TTS=piper` + `HF_HUB_OFFLINE=1`：**全离线**（gTTS 在 GPU 机不可达，所以必须用 piper）。
  - 模型在 `openpronounce-deploy/models/hf`，合成缓存在 `openpronounce-deploy/cache`。
- **上游契约**（`GET /openapi.json` 是权威，README 落后于线上）：
  | 端点 | Content-Type | 说明 |
  |---|---|---|
  | `POST /pronunciation` | `multipart/form-data` | `file` + `expected_text` + `lang` → 完整评测 JSON |
  | `POST /speech2text` | `multipart/form-data` | `file` + `lang` → `{"transcript":...}` |
  | `POST /phonemes` | **`application/x-www-form-urlencoded`** | `text` + `lang` → `{"words":[],"phonemes":[]}` |
  | `POST /tts` | **`application/x-www-form-urlencoded`** | `text` + `lang` → 16k 单声道 wav 字节 |
  | `GET /languages` / `GET /health` | — | 语言表 / 存活 |
- **我方对接**（分层，换供应商只改实现类 + 一行配置）：
  - `trigger`: `PronunciationController`（`/api/v1/sessions/{id}/pronunciation`）+ `PronunciationToolController`（`/api/v1/pronunciation/*`）。
  - `case`: `cn.adinall.ai.cases.pronunciation.IPronunciationCase` / `PronunciationCaseImpl` / `PronunciationAssembler`。
  - `domain`: `IPronunciationPort`（端口）、`IPronunciationService`、`PronunciationAssessmentAggregate`、`PronunciationResultVO` 等。
  - `infrastructure`: `OpenPronounceGateway`（唯一讲 OpenPronounce 方言的地方）、`OpenPronouncePronunciationAdapter`、`OpenPronounceProperties`（前缀 `spoken-ai.provider.openpronounce`）、`PronunciationConfiguration`。
  - 落库：`pronunciation_assessment` 表（Flyway `V3`），完整结果存 `result_json`，另存 score/错误率等标量列供查询。
- **接口清单**：
  | 方法 | 路径 | 说明 |
  |---|---|---|
  | POST | `/api/v1/sessions/{id}/pronunciation` | 跟读评测（multipart：`file`/`expectedText`/`lang`/`turnId`）→ 分数+错词+韵律，**并落库** |
  | GET | `/api/v1/sessions/{id}/pronunciation` | 本场评测记录（倒序） |
  | GET | `/api/v1/pronunciation/languages` | 语言表 |
  | GET | `/api/v1/pronunciation/health` | 探针（**永远 200**，body 里 `status=ok/down`） |
  | POST | `/api/v1/pronunciation/speech2text` | 纯识别（multipart） |
  | POST | `/api/v1/pronunciation/phonemes` | 音素化（form） |
  | POST | `/api/v1/pronunciation/tts` | 参考音合成（form）→ `audio/wav` |
- **音频从哪来（已定案）**：**App 侧截 AEC3 后的 PCM，随 `POST /voice` 以 octet-stream 上传给 Java**。
  - 关键结论：**AEC3 不是 OpenTalking 做的，是 Android 端做的**（WebRTC SDK 的 native APM，靠 `createAudioSource` 的 `googEchoCancellation2=true` 打开）。OpenTalking/aiortc 只做「Opus 解码 + 重采样到 16k」，全仓搜不到任何 AEC 实现。
  - 所以「干净的音频」只能从 WebRTC APM 输出端取：App 用 `PeerConnectionFactory.Builder.setAudioProcessingFactory(ExternalAudioProcessingFactory)` + `setCapturePostProcessing(...)` 在 AEC3 **之后**截一份 PCM。⚠️ 换自定义 factory 后 AEC3 是否仍开启需**真机验证一次**。
  - 麦克风音轨仍要 `addTrack`（这是让 WebRTC 采集 + AEC 跑起来的开关），但 `setMicSending(false)` 永不发包——OpenTalking 不再需要收到上行。
- **产品现状**：上游支持 7 种语言，但**只有英语对着人工评分校准过**（其余复用英语的音素阈值）。想在界面上只放英语，把 `spoken-ai.provider.openpronounce.expose-languages` 配成 `[en]` 即可。

### 2.8 Java 接管 LLM（评测嵌入对话主链路，2026-10-10）
- **背景**：迁移前 OpenTalking 是 STT+LLM+TTS 一条龙，评测插不进去。现在 Java 自己跑 LLM，于是评测能插在 STT 与 LLM 之间。
- **一轮对话的新链路**（`POST /voice`，`llm-owner=agent` 时）：
  ```
  App 上传 AEC 后 PCM（octet-stream）
    → WavEncoder 封 WAV
    → OpenTalking /transcribe（只识别、不说话）
    → 落回合 + 推 stt.final
    → OpenPronounce 评测（参照文本 = expectedText 或识别文本）→ 落库 + 推 assessment.done
    → 拼提示词（用户文本 + 评测分数/错词）→ Google ADK agent 生成（内部流式收增量）
    → 收齐整段文本 → **一次** /say（整段交上游 TTS + 数字人动作）
    → 上游 speech.ended → assistant.done：字幕定稿、复位播放状态、回填回合的 assistantText
  ```
- **HTTP 响应**：`{"text","turnId","assessment"}`（向后兼容扩充，assessment 为 null 时该键不下发）。
- **关键开关/配置**：
  - `spoken-ai.llm-owner: agent|opentalking`（默认 agent；opentalking 退回老链路 `/speak_audio` 一条龙）。
  - `spoken-ai.reply.agent-id: "100003"`（用哪个智能体）。
  - `ai.agent.config.enabled: true` + 环境变量 `LK888_API_KEY`（agent 装配必需）。
- **新增类**：`IReplyGeneratorPort`/`AgentReplyGenerator`（ADK→文本增量）、`ConversationServiceImpl.converseTurn`、`ReplyPromptBuilder`、`WavEncoder`、`IAvatarDriverPort.transcribeUploaded`。
- **字幕由上游驱动**：agent 模式下 Java **不**自己推 `assistant.delta`（`onReplyDelta` 只把增量累加进缓冲），字幕、播放状态与回合落库都搭上游 `/say` 那套 `speech.started` / `subtitle.chunk` / `speech.ended` 事件的车 —— 这样天然不会重复。
  （2026-10-12 曾短暂改成「Java 自己推字幕 + 逐句 /say」，那时需要在上游事件流里按 llm-owner 过滤 assistant 事件；该版本已回退，过滤也随之删除。）
- ⚠️ **别再试「逐句流式下发」**（2026-10-12 试过并回退）：延迟大头是模型 TTFT（直连实测 2~15s，波动极大），流式换不来可测收益，却多出一套事件协议与状态机（还踩过 `command_id` 撞 id 导致第二轮 409）。完整复盘见 [`product-design.md`](./product-design.md) §5.3。**要再快只能换更快的模型 / 服务商。**
- **遗留坑（agent 脚手架从未真跑过）**：`agent/spoken-server.yml` 原先缺 `module.agents`（智能体列表）和 `module.runner.agent-name`（入口），`enabled=true` 一开就 NPE（`agents is null`，见 AgentNode:85）。已补齐：`agents:[{name:spokenCoach,...}]` + `runner.agent-name: spokenCoach`。

---

### 2.9 智能体人格契约与分级语言策略（2026-10-10）

产品目标不是「纠错」，而是让学员**愿意继续说**。两条准入要求落成了可执行的契约：

- **第①层 零评判**：不许出现 wrong / incorrect / mistake / error / mispronounced，不许报分数、不许提「评测」二字；
  要纠正就说「A more natural way to say that is ...」，**一轮最多提一条**。
- **第②层 会追问**：每轮回复必须以**一个基于学员刚说的具体细节**的问句收尾；禁止 "And you?" 这类万能追问，
  禁止复述学员原句。**追问率才是这套产品的质量指标**，不是「答对了」。

职责切分（2026-10-13 更新）：

```
agent/spoken-server.yml 的 instruction       SYSTEM 基础契约 + {turn_policy?}
ReplyPromptBuilder.buildSystemPolicy        本轮六级语言规则 / 唯一任务 / 命中结果 -> SYSTEM
ReplyPromptBuilder.buildManaged             当前用户原话 / 上轮任务数据 -> USER
ChatContextService                          历史与记忆裁剪，预留静态和动态 SYSTEM 容量
ChatService.handlePreparedTextStream        独立短会话 state 注入规则；结束、失败、取消均删除
ScenarioVO                                  free/interview 不再带 Amy/Alex 专属提示词
```

语言策略按冻结的 L1～L6 逐轮生成，通过 ADK state 注入 SYSTEM；场景与旧英文历史不能覆盖。
L1/L2 使用简短中文支持和对应难度的英文；L3 简单英文配必要中文；L4 英文优先且允许中文；
L5/L6 自然或丰富但简洁的英文，求助时追加中文解释。全英文是否合适取决于等级与本轮需要，
没有统一的全英文禁令。下表是旧三档背景，生产回复以六级策略为准。

| 档位 | 语言策略 |
|---|---|
| `LOW`  | 主要用中文陪，给一句 3~6 词的英文示范让他借用，并用中文邀请他跟读 |
| `MID`  | 简单英文短句；个别生词后面括注中文 |
| `HIGH` | 全英文，不许出现中文字符 |

档位判定（`LearnerLevelTracker.judge`）：

| 信号 | 分值 |
|---|---|
| 完全没识别出内容 / 文本含中文 | -2 |
| 英文词数 1~2 | -1 |
| 英文词数 ≥ 8 | +1 |
| 评测分 < 60 / ≥ 85 | -1 / +1 |

合计 ≥1 → HIGH；≤-2 → LOW；否则 MID（门槛设 -2 是为了让「只答一句 Yes」不被打到中文档）。
**下调立刻生效、上调需连续 2 轮，且一次只挪一档**（LOW→MID→HIGH，不许跳级）。
真机实测演进：中文→LOW，英语×2→MID，英语×2→HIGH。

**档位只影响我方措辞，绝不下发给 App 展示** —— 一旦显示就变成「系统在给我打等级」，与零评判冲突。
初始档位：**有历史就用长期水平**（`user_learning_stat.long_term_level`，长期 LOW → 开场用中文），
**没历史才从场景难度推且只往高推**；长期水平必须按近期表现算，否则一次糟糕经历会把人钉死在 LOW。
开场白走脚本轨（不经 LLM），所以必须**按档位存多份**（`opening_line_low/mid/high`）——
规则、护栏与断点见 [`product-design.md`](./product-design.md) §3.4。

场景人设这次才真正进了 agent（此前 `systemPromptHint` 只发给了我们用不到的上游 LLM，等于白写）：
`free`=Amy、`interview`=Alex。`ConversationServiceImpl` 因此多注入了 `IScenarioRepository`。
回复原文会打进日志（`回复已下发 ... text=...`）—— 人格契约是否被遵守只能看原文，这是它唯一的可观测手段。

---

## 3. 后端代码结构（本目录）

```
spoken-server/
├── types/          ResponseCodeEnum（对话码，含 pronunciation_failed / audio_too_large）、AppException（双约定）、ResponseCode（agent 码，别混）
├── api/            IAgentService（agent 端口）+ DTO（含迁入的对话 DTO，多为 Java record；发音评测 DTO 也在这里）
├── domain/
│   ├── agent/      脚手架自带 agent 框架（armory/chat/workflow/mcp/skills），原样未动
│   ├── conversation/  迁入的对话领域 + 发音评测领域
│   │                  IPronunciationPort / IPronunciationService / IPronunciationAssessmentRepository
│   │                  PronunciationAssessmentAggregate、AssessPronunciationCommandEntity
│   │                  PronunciationResultVO / ErrorVO / PhoneVO / ProsodyVO / LanguagesVO / PhonemesVO
│   └── shared/        IAppConfigPort（adapter/port）
├── case/           编排层（catalog/conversation/pronunciation），注意包名是 `cases`（case 是关键字）
├── infrastructure/ OpenTalking gateway/驱动/事件翻译/仓储/DAO/PO/配置
│                   + OpenPronounceGateway / OpenPronouncePronunciationAdapter / OpenPronounceProperties
│                   + PronunciationAssessmentRepository / IPronunciationAssessmentDao / PronunciationAssessmentPO
├── trigger/        SessionController + CatalogController + GlobalExceptionHandler + AgentServiceController
│                   + PronunciationController + PronunciationToolController
└── app/            Application（入口）、Cors、application.yml/application-dev.yml、MyBatis mapper
                  resources/db/migration/V1~V3（Flyway）
```

分层约定：`trigger → case → domain → infrastructure`；HTTP 契约（端点路径 + JSON 形状）**不能变**，Android 依赖它。

---

## 4. GPU 机上的 OpenTalking 补丁（生产运行依赖，无源码在本地）

- OpenTalking（`/home/nm/develop/code/opentalking-main`，**无 git**）已改 5+ 个文件：`/say` 脚本轨、`direct` 透传、STUN 可关闭、`attach_timeout`、WebRTC 上行收音频（`on("track")` + `take_mic_pcm`）。有快照 `backup/opentalking-main-snapshot-*`。
- SoulX-FlashHead（有 git，分支 `spokenai/adaptive-aspect`）：按形象自适应分辨率。
- omnirt（有 git，分支 `spokenai/flashtalk-patch`）。
- 桥接脚本 `flashhead_server.py` 已迁到 `/home/nm/develop/code/spokenai-bridge/`（原位置留符号链接）。
- **改 OpenTalking 侧代码要小心**：单槽锁、状态串台（`pipeline`/`PARAMS` 是进程级全局、`latent_motion_frames` 逐块滚动）都是硬约束。

---

## 5. 踩过的坑（务必先读，避免重犯）

1. **`-parameters` 编译参数**：迁入的 DTO 大量用 Java record，Spring/Jackson 反序列化 record 靠参数名，必须开 `-parameters`。脚手架的 `maven-compiler-plugin` 原来是 `3.0`（2013 年的，不认识 `<parameters>`），已改 `3.13.0`。症状：`HTTP 400 Name for argument of type [java.lang.String] not specified`。
2. **`case` 是 Java 关键字**：包名用 `cn.adinall.ai.cases`（复数），不能用 `cn.adinall.ai.case`。
3. **`CreateSessionRequestDTO` 命名冲突**：脚手架本就有（agent 的，agentId/userId），迁入的对话同名 DTO 会覆盖它。对话侧已改名 `CreateConversationSessionRequestDTO`。
4. **`training_session` 表是"会话档案"**：命名沿用旧脚手架，实际存的是对话会话（不是训练域）。`ConversationSessionRepository` 靠它落库（`ITrainingSessionDao` + `TrainingSessionPO`）。想清理时再改名 `conversation_session`。
5. **场景查找走 `IScenarioRepository`**：原代码把 `findScenario` 挂在 `IAssessmentService`（训练域），迁移时改成了直接走 `IScenarioRepository`。
6. **`session_service.speak` 有 `payload` 和 `task` 两个字典**：透传字段必须改 `task`，只改 `payload` 会静默丢失。
7. **`OPENTALKING_*` 环境变量写 `.env` 不生效**：只喂 pydantic Settings，`os.environ` 读的开关必须在进程环境 export。
8. **AppException 双约定**：脚手架的 agent 用 `getCode()/getInfo()`，对话代码用 `code()/getMessage()`。已合并兼容，两套都别删。
9. **两套响应码别混**：`ResponseCodeEnum`（对话，"success"/"session_not_found"…）和 `ResponseCode`（agent，"0000"/"E0001"…）是两套契约。
10. **MySQL 语法**：Flyway 迁移里不能用 `CREATE INDEX IF NOT EXISTS`、`ALTER ADD COLUMN IF NOT EXISTS`、`generated by default as identity`（H2 语法）。MySQL 用 `auto_increment`、无 IF NOT EXISTS。
11. **脚手架残留**：`frame_case_mapper.xml` 是占位（引用不存在的 `Xxx`/`A` 类），已删。删文件后要 `clean package`，否则旧资源残留在 jar。
12. **AiAgentAutoConfig 无配置会 NPE**：已加 `enabled=false` + 空表守卫，agent 未启用时跳过，对话后端可独立启动。
13. **H2 → MySQL**：数据存储已统一 MySQL（`172.18.0.102:13306/spoken`），H2 已移除。Flyway 迁移带表/字段注释。
14. **记忆功能是死代码**：`has_memory` 恒为 False（`memory_enabled` 与 `character_id` 互斥逻辑导致），简单记忆永远不生效。这是已知遗留，不是 bug 触发路径。
15. **Flyway 迁移脚本丢了（2026-10-10 已修）**：`V1__init.sql` / `V2__session_scenario.sql` **从未提交进 git**，只作为本地未跟踪文件存在，后来被删掉；而 MySQL 的 `flyway_schema_history` 里已经记着应用过 V1/V2。症状：
    - 库里表都在、老环境却**启动失败**：`Migration checksum mismatch for migration version 1/2`（Flyway 校验本地脚本与历史记录的 CRC 不一致）。
    - 全新环境反而建不出表（没有任何迁移文件可跑）。
    **修法**：按线上库实际结构重建 `V1__init.sql` / `V2__session_scenario.sql`，然后把 `flyway_schema_history.checksum` 对齐成 Flyway 报的 `Resolved locally` 值（就是 `flyway repair` 干的事），再加 `V3`。**教训：迁移文件必须提交 git；改动已应用的迁移文件后必须跑 repair，否则一律启动失败。**
16. **Spring multipart 默认上限只有 1MB**：跟读评测上传录音会直接撞 `MaxUploadSizeExceededException`，而且是在 Controller 之前抛的。已在 `application-dev.yml` 放宽到 `max-file-size: 16MB` / `max-request-size: 20MB`，并把该异常映射成 **413 audio_too_large**（不映射的话会落兜底变成 500）。
17. **OpenPronounce 的 `/phonemes` 与 `/tts` 收的是 `application/x-www-form-urlencoded`，不是 JSON body**：FastAPI 的 `Form(...)`。用 JSON 打过去得到 **422**（不是「安静地当成没传」）。`/pronunciation` 与 `/speech2text` 才是 `multipart/form-data`。权威依据是 `GET http://172.18.0.102:8070/openapi.json`。
18. **OpenPronounce 线上响应比它的 README 多好几个字段**：多了顶层 `distance`/`feedback`/`language`，`differences` 里多了 `word_distance`/`phoneme_distance`/`words_with_errors`/`expected_phonemes`/`transcribed_phonemes`，`errors[]` 多了 `actual_word`/`phone_distance`。**别照 README 建模，照 `/openapi.json` + 一次真实调用**。
19. **`GlobalExceptionHandler.statusOf` 原来的兜底是 502**：领域层主动抛的 `invalid_request`（缺参数、空音频）和 413 都会掉到 502。已补上 `INVALID_REQUEST→400`、`AUDIO_TOO_LARGE→413`，并新增 `MissingServletRequestPart/ParameterException→400`、`MaxUploadSizeExceededException→413` 两个处理器。

---

20. **OpenTalking 的 `/say` 与 `/speak` 不继承建会话时的 `tts_provider`**（2026-10-10 实测）：
    上游在这两个端点是**请求级解析**引擎的（`_effective_tts_provider(requested)`），没带就回退到**上游全局默认**
    （当时是 `edge`）。表现：建会话传了 `tts_provider=dashscope` 也成功，但一 `/say` 就报
    `400 invalid Edge TTS voice id: 'Cherry'`。**引擎必须每次发声请求都带**。
    另外音色是**按引擎命名空间**命名的（`dashscope/Cherry`、`edge/en-US-AriaNeural`、`xiaomi_mimo/Mia`），
    **换音色必须同时换 `spoken-ai.provider.opentalking.default-tts-provider`**，只换名字不换引擎解不出来。
    百炼（dashscope）TTS 需要 `OPENTALKING_TTS_DASHSCOPE_API_KEY`，与 STT 的 Key 可共用同一个；
    `POST /runtime-config/apply` 可热更新且**会持久化写回 OpenTalking 的 `.env`**，不必重启。

21. **Android 侧 WebRTC AEC 采集缓冲是 float32，且数值已在 int16 量程内**（2026-10-10 实测，整条 STT 链路的隐形杀手）：
    真机缓冲 1920 字节 = 480 帧 × **4 字节**，每 10ms 一次 → 48kHz 单声道（`initialize` 报 `channels=1` 是对的，
    4 字节不是立体声而是 float32）。**两个坑叠在一起**：
    (a) 按 int16 解 → 得到噪声，STT 永远回「没有识别出有效语音」；
    (b) 按 float32 解但乘 32767 → 缓冲里的数值是 ±6500 量级（**已经是 int16 量程，不是 [-1,1] 归一化**），
        乘完全部撞到 ±32767 变成**满量程方波**（电平表 RMS/Peak 都显示 0dBFS，看着像「有声音」）。
    正确做法：**直接取整，不乘系数**。离线验证方式：把缓冲落盘，用四种解释分别灌 STT 比对，一次定案。

22. **Reactor 的 `map` 不允许返回 null**：返回 null 会抛
    `NullPointerException: The mapper ... returned a null value`，**整条 SSE 流当场断掉**，
    后面的 `filter(Objects::nonNull)` 根本没机会执行。表现是 App 显示「连接已断开」。
    要丢弃事件只能用**前置 `filter`**，不能在 `map` 里返回 null。

## 6. 迁移记录（spoken-ai → spoken-server）

**迁入**：conversation 域 + catalog + OpenTalking 集成（gateway/驱动/事件翻译/仓储/DAO/PO）+ case 层 + trigger（Session/Catalog/异常处理）+ 相关 DTO/types。

**明确不迁（无用/后续重建）**：Volcengine 全套、Mock 适配器、`SessionFlowMockTest`、**training/评测域**（AssessStrategy 等）、**translate/翻译**。

**关键适配**：`com.spokenai` → `cn.adinall.ai`；`domain.conversation` → `domain.business.conversation`；`domain.shared` → `domain.business.shared`；场景从 `domain.training` 迁到 `domain.business.conversation`；case 层新建模块。

**2026-10-10 扁平化（去掉 business 这层）**：`domain.business.conversation` → `cn.adinall.ai.domain.conversation`，`domain.business.shared.adapter.port` → `cn.adinall.ai.domain.shared.adapter.port`；`domain/business/` 下已无实现类（只剩脚手架空 `package-info`），一并删除。现在 `domain/` 下只有 `agent/`、`conversation/`、`shared/` 三个包。跨模块引用（case/infrastructure/app）的 import 同步改完，无 XML/YAML 配置耦合这两个包名。

**扁平化后的验证（2026-10-10）**：`mvn -DskipTests clean package` 在 api / types / domain / case / trigger / infrastructure 六个模块全部 SUCCESS；app 模块只在 `clean` 阶段失败——**原因是 8091 上正在运行的后端进程锁住了 `spoken-server-app/target/ai-agent-scaffold-lite-app.jar`**（不是编译错误）。随后用 `mvn -DskipTests -pl spoken-server-app -am compile` 验证 app 的 8 个源文件编译通过（BUILD SUCCESS）。**要产出新的可执行 jar，必须先停掉 8091 上的旧进程再 `clean package`。**

**验证状态（迁移当时）**：`mvn clean package` 通过；启动后 catalog 四接口 + 建会话 + 手机端到端（数字人画面 + 开场白）都验证过。

---

## 7. 环境与运行

### 服务端口（都在 GPU 机 `172.18.0.102`，除 Java 后端在 PC）
| 服务 | 端口 |
|---|---|
| OpenTalking | 8001（HTTP）、WebUI 5173 |
| FlashHead WS | 8766 |
| FlashTalk WS | 18766 |
| MySQL | 13306（root/123456，库 `spoken`） |
| **OpenPronounce** | **8070**（docker 容器内 8000；全离线，piper TTS） |
| **Java 后端（本目录）** | **8091**（dev profile；旧 spoken-ai 是 8080） |

### 构建/运行
```bash
# Java 后端（Windows PC）
set JAVA_HOME=D:\develop\evn\jdk
mvn -DskipTests clean package   # Maven: C:\Users\16639\.spokenai-tools\apache-maven-3.9.9
java -jar spoken-server-app/target/ai-agent-scaffold-lite-app.jar
# 配置在 application-dev.yml（active=dev），端口 8091
```

### 关键配置（`application-dev.yml`）
- `spoken-ai.provider.opentalking.base-url = http://172.18.0.102:8001`（换数字人后端改这一处）
- `spoken-ai.provider.opentalking.drop-stun = true`（局域网直连摘 STUN，省 ~7s）
- 数据源 MySQL `172.18.0.102:13306/spoken`，`createDatabaseIfNotExist=true`

### Android（`D:\develop\code\project\jugao\android`，与本仓库同级）
- 构建：Gradle `C:\Users\16639\.spokenai-tools\gradle-8.11.1`，SDK `C:\Users\16639\.spokenai-tools\android-sdk`
- 手机：小米 8（`b955e2bc`）；后端地址在 App「后端地址」里配（当前 `http://192.168.20.41:8091`）
- 关键文件：`feature-talk/`（`TalkViewModel`、`VoiceSegmenter`、`VoiceRecorder`、`TalkScreen`、`ConnectScreen`）、`core-rtc/`（`SpokenRtcClient`，WebRTC+上行音轨+AEC）、`core-net/`（`SpokenAiApi`、`ServerConfigStore`）

---

## 8. 当前状态与待办

**已完成**：
- 数字人对话后端迁入 spoken-server，编译/启动/端到端验证通过。
- 存储统一 MySQL + 表/字段注释。
- WebRTC 上行 + AEC3 回声消除；语音识别音频从上行缓冲取。
- 手机连 8091 测试通过。
- **跟读发音评测对接 OpenPronounce（2026-10-10）**：端口+适配器+网关+用例+Controller 全套；评测结果落库（`pronunciation_assessment`，Flyway V3）；顺带修掉丢失的 Flyway V1/V2。
- **Java 接管 LLM + 评测嵌入对话主链路（2026-10-10）**：`/voice` 新语义（上传 AEC 后 PCM → STT → 评测 → agent 生成 → 整段 /say → SSE），端到端验证通过（turnId 关联、agent 正确引用评测结论、SSE 无重复）。见 §2.8。

**待办**：
1. **App 侧接入（Android 在 `D:\develop\code\project\jugao\android`，不在本仓库）**：
   - 用 `setCapturePostProcessing` 截 AEC3 后 PCM，**真机验证换自定义 factory 后 AEC3 仍开启**（这是唯一不确定项）。
   - 把 `POST /voice` 的 body 从空改成裸 PCM（octet-stream），处理新响应 `{text,turnId,assessment}` 渲染评测卡片（`ChatLine.assessment` + `AssessmentChip` 已就绪）。
   - 删掉调不通的 `POST /api/v1/training/assess`（该接口 8091 上不存在，且调用处只有 onSuccess、失败静默）。
2. **回答策略微调**：`ReplyPromptBuilder`（提示词）与 `agent/spoken-server.yml` 的 instruction 是「怎么回应 + 纠音」的唯二落点，按产品口味调。
3. **命名清理（可选）**：`training_session` → `conversation_session`。
4. **旧 spoken-ai 后端下线**：8080 上的旧后端（若还跑着）确认后可停。
5. **GPU 单槽扩展**：如需多用户并发，见旧文档 `work/direct-say/R1-single-gpu-slot-research.md`（方案 A/B 调研）。

---

## 9. 术语表

| 术语 | 含义 |
|---|---|
| OpenTalking | GPU 数字人引擎（Python，172.18.0.102:8001） |
| OpenPronounce | GPU 发音评测引擎（docker，172.18.0.102:8070；MIT，音素级评分） |
| 跟读评测 | 用户跟读一句题面 → 录音送 OpenPronounce → 分数/错词/韵律，落 `pronunciation_assessment` 表 |
| FlashHead / FlashTalk | 数字人画面模型 / 音频驱动模型 |
| 生成轨 / 脚本轨 | `/speak`（LLM 生成）/ `/say`（直通 TTS） |
| STT / TTS | 语音识别（dashscope）/ 语音合成（edge-tts） |
| AEC / AEC3 | 回声消除 / WebRTC 软件回声消除 |
| WebRTC 上行 | App 麦克风音轨 → 服务端（带 AEC3），非 HTTP 上传 |
| case 层 | 用例编排层（`cn.adinall.ai.cases`，模块 `spoken-server-case`） |
| DDD 端口 | `IAvatarDriverPort` 等，领域声明、infra 实现 |

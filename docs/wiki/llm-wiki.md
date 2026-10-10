# 数字人口语训练项目 — LLM Wiki

> **这份文件是给「AI / 新人」衔接用的项目记忆。**
> 换工作目录、开新会话时，先让 AI 读本文件，即可直接衔接，不必重新摸一遍。
> 最后更新：2026-10-10。维护原则：每次有架构级改动，同步更新本文件。

---

## 0. 30 秒速览

- 这是一个「**英语口语陪练**」产品：用户对手机说英语，数字人（AI 形象）用英语陪练。
- 三端链路：**Android App（Kotlin/Compose）→ Java 后端 → OpenTalking（GPU 数字人引擎）**。
- 本目录 `spoken-server` = **Java 后端**（当前开发主战场），包名 `cn.adinall.ai`，Spring Boot 3.4.3 + Google ADK agent 脚手架 + 迁入的数字人对话后端。
- 后端当前定位是「**嘴和耳朵**」：收语音（STT）、让数字人说话（TTS/画面）。**LLM 对话策略后续由用户用 agent 自己接管**（尚未做）。

---

## 1. 代码位置地图（务必先看）

| 位置 | 内容 | 状态 |
|---|---|---|
| `D:\develop\code\project\jugao\spoken-server`（本目录） | Java 后端（`cn.adinall.ai`） | **当前主战场** |
| `C:\Users\16639\Documents\Codex\2026-09-23\nm-172-18-0-102-opentalking\spoken-ai` | 迁移来源：Android App（`android/`）+ 原 Java 后端（`com.spokenai`） | 已迁移，旧 Java 后端基本被 spoken-server 取代；**Android 仍在这里** |
| GPU 机 `172.18.0.102` 上 `/home/nm/develop/code/` | OpenTalking / SoulX-FlashHead / omnirt（有本地补丁） | 生产运行中，见 §4 |

**重要**：Android App 代码**没有**迁到本目录，还在旧目录的 `android/` 下。改 App 要去那边。

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

---

## 3. 后端代码结构（本目录）

```
spoken-server/
├── types/          ResponseCodeEnum（对话码）、AppException（双约定）、ResponseCode（agent 码，别混）
├── api/            IAgentService（agent 端口）+ DTO（含迁入的对话 DTO，多为 Java record）
├── domain/
│   ├── agent/      脚手架自带 agent 框架（armory/chat/workflow/mcp/skills），原样未动
│   ├── conversation/  迁入的对话领域（adapter/port、adapter/repository、model/aggregate|entity|valobj、service）
│   └── shared/        IAppConfigPort（adapter/port）
├── case/           编排层（catalog/conversation），注意包名是 `cases`（case 是关键字）
├── infrastructure/ OpenTalking gateway/驱动/事件翻译/仓储/DAO/PO/配置
├── trigger/        SessionController + CatalogController + GlobalExceptionHandler + AgentServiceController
└── app/            Application（入口）、Cors、application.yml/application-dev.yml、MyBatis mapper
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

---

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

### Android（在旧目录 `spoken-ai/android/`）
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

**待办（用户后续自己做）**：
1. **agent 接管 LLM 会话文本**：用户自己管理 LLM 对话（agent 出文本 → `/say` 念），脚手架 `domain.agent` 框架已就位。
2. **口语训练回答策略**：替换掉原来的发音评测（training 域未迁）。
3. **命名清理（可选）**：`training_session` → `conversation_session`。
4. **旧 spoken-ai 后端下线**：8080 上的旧后端（若还跑着）确认后可停。
5. **GPU 单槽扩展**：如需多用户并发，见旧文档 `work/direct-say/R1-single-gpu-slot-research.md`（方案 A/B 调研）。

---

## 9. 术语表

| 术语 | 含义 |
|---|---|
| OpenTalking | GPU 数字人引擎（Python，172.18.0.102:8001） |
| FlashHead / FlashTalk | 数字人画面模型 / 音频驱动模型 |
| 生成轨 / 脚本轨 | `/speak`（LLM 生成）/ `/say`（直通 TTS） |
| STT / TTS | 语音识别（dashscope）/ 语音合成（edge-tts） |
| AEC / AEC3 | 回声消除 / WebRTC 软件回声消除 |
| WebRTC 上行 | App 麦克风音轨 → 服务端（带 AEC3），非 HTTP 上传 |
| case 层 | 用例编排层（`cn.adinall.ai.cases`，模块 `spoken-server-case`） |
| DDD 端口 | `IAvatarDriverPort` 等，领域声明、infra 实现 |

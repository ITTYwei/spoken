# 数字人主界面与课程陪练：第 0 步验证

实施日期：2026-10-14。范围仅为参考源码、内容审计、在线只读工具验证和媒体实现核查。
第 1 步主界面、课程接口及用户设置工具尚未实施；每步交付后等用户确认，再进入下一步。

## 1. 源码与教材来源

- 源码地址：https://github.com/Luxshan2000/ielts-prep
- 本地目录：`D:\develop\code\github\ielts-prep`。
- 固定参考提交：`10282cc853a03b6984907fec0affe6cbbd4fe0b0`，提交日期 2026-09-09。
- 下载后工作区干净，未运行其程序、安装依赖或连接其服务。
- 代码根目录 `LICENSE` 为 MIT；`content/core-en/manifest.json` 与内容 README 分别声明自有教材包为 CC0-1.0。
- 内容包 `org.bandready.core-en` 版本 1.0.0，自述为 AI 辅助编写、人工检查的原创练习，非官方雅思真题。该说明是上游声明，不代表本项目完成了独立教学质量评审。

核查了 manifest 中全部 22 个文件的 SHA-256。Windows Git 默认将 LF 转换为 CRLF；还原为 LF 后全部与 manifest 一致，22 项通过。实际读取到 496 张口语卡，与 manifest 数量相同。
未将全部题库导入应用，也没有把本地参考目录作为运行时依赖。

后续首课候选为 `card_p1_food_cooking_001`（Part 1，`topic_food`），五个问题围绕做饭、在家用餐与口味变化。
导入时保留其题目来源，并另行自编中文标题、目标、重点表达和分档开场；不把这些新增字段冒充上游教材。

### 值得参考的实现

| 上游位置 | 可参考的设计 | 本项目使用边界 |
| --- | --- | --- |
| `content/core-en/data/topics.jsonl`、`card_sets.jsonl`、`speaking_cards.jsonl` | 话题、关联题组和单张问题卡分离 | 转换为本项目 scenario 的内容模型，practice 管理选课状态 |
| `sidecar/bandready/voice/state_machine.py` | 状态由服务端持有，前端镜像；考试与陪练提示词分开 | 当前实现话题陪练，不搬考试计时及强制逐题流程 |
| `sidecar/bandready/speaking/coach.py` | 原题与教学辅助分开，参考答案有展示条件 | 后续按本项目帮助/跟读策略使用，不直接搬高分答案或评分规则 |
| `sidecar/bandready/speaking/drills.py` | 练习围绕当前卡；可确定的检查由程序处理，歧义再交模型 | 借鉴规则与 Agent 分工，不移植评分和模型客户端 |

上游陪练提示词包含能力表扬和重试邀请，考试提示词又限制解释和帮助；两者都不能直接覆盖本项目的零评判、中文帮助与每轮唯一任务规则。

## 2. 现有脚手架在线工具验证

新增 `AgentReadOnlyToolLiveTest`，仅为显式开启的能力探针，不挂入生产 Agent，也不增加业务 API。
遵循本地 `.skill-sources/xfg-ddd-skills/SKILL.md`，保留 Agent、Case 与基础设施的既有组织方式。

真实验证链路：

```text
读取生产 YAML 的内存副本
  -> LocalToolMcpCreateService 获取测试 ToolCallbackProvider
  -> AiApiNode -> ChatModelNode -> AgentNode -> AgentWorkflowNode -> RunnerNode
  -> DefaultArmoryFactory -> ChatService
  -> bonsai-2-27b 实际发起只读工具调用
  -> Spring AI 执行工具并交回结果
  -> AgentTextStream 输出可播报文本
```

测试用工具返回运行时随机凭据；同步和流式各自重新生成凭据并独立计数，最终回复必须包含各自的真实工具结果，流式回复不能包含原始工具 JSON 字段名。
只有工具注册能力在测试容器中改变；没有直接构建另一套模型客户端，没有启动完整 Spring Boot 应用，因此没有连接业务库、运行迁移或申请数字人槽位。

生产模型配置沿用 YAML，但测试 Agent 的 instruction 改为一个明确的只读目录查询任务。
这证明传输和执行链路可用，不代表真实用户意图识别、中文多轮澄清或写工具校验已经完成。

最终实测：同步与流式各执行一次工具，两条回复都使用对应结果；两条路径合计 3038ms。
定向回归包括工具探针、会话隔离/清理、文本事件转换、模型流取消及装配参数，共 **14 项通过，0 失败、0 跳过**；所有 Maven 模块构建成功。

复现方式（仓库根目录，需进程环境变量 `LK888_API_KEY`，不将密钥写进命令或文件）：

```powershell
mvn "-Dmaven.repo.local=D:/develop/env/mvn_repo" "-DskipTests=false" "-Dtest=AgentReadOnlyToolLiveTest,AgentSessionIsolationTest,AgentTextStreamTest,AgentModelStreamTest,ChatModelNodeTest" "-Dsurefire.failIfNoSpecifiedTests=false" "-DfailIfNoTests=false" "-Dspoken.verify.live-tools=true" test -pl spoken-server-app -am
```

普通测试未设置 `spoken.verify.live-tools=true` 时跳过在线探针。验证报告只含模型名、次数、耗时和结果，不保存鉴权信息或真实用户数据。
首次受限环境运行因网络权限失败；取得网络执行权限后完成上述真实验证。

## 3. 媒体预连接核查及第 1 步前置条件

检查了相邻 Android 项目实际实现，并通过只读 SSH 查看了 GPU 服务当前 `handle_offer` 源码，未创建或关闭其任何会话。

已确认：

1. Android `SpokenRtcClient.start` 为视频创建 RECV_ONLY 收流通道，但音频立即通过 `attachMicTrack` 挂载麦克风音轨。
2. `setEnabled(false)` 只设置音轨状态，不能当作没有麦克风采集的证明；现有代码注释也明确采集持续运行以支持 AEC。
3. `TalkViewModel.startMedia` 接通后立即调用 `speakOpening`，当前无法静默待机。
4. 实际 GPU 服务 `opentalking/providers/rtc/aiortc/adapter.py` 第 601～605 行，在已有 PeerConnection 上处理 offer 并返回 answer，没有在此入口强制创建另一场媒体会话。

建议第 1 步沿用同一媒体会话：待机先为音频/视频使用接收通道，用户主动开启语音时再挂载采集音轨并协商；开场由独立的用户开始意图触发。
这是源码支持的实现方向，**尚未验证动态挂载后 Android AEC、同一音频通道复用、服务端二次协商及画面连续性**，不能宣传为已通过真机测试。

第 1 步必须先验证：无麦克风权限时能接收画面和声音；待机没有录音指示和采集；开始只播一次；启用语音后二次协商和 AEC 正常；60 秒未开始、退后台和退出登录能够释放槽位。
预连接只证明媒体通道准备好，是否持续有待机画面还需实际收到视频帧验证。若运行协议不能满足预期，先提交证据和调整方案供用户确认，不悄悄改为自动录音或只显示封面。

## 4. 本次改动与完成边界

- 新增一项带详细中文注释、默认关闭的在线脚手架探针。
- 新增本记录，更新 LLM Wiki 的衔接入口。
- 生成构建产物：`spoken-server-app/target/stage-zero-tool-live-report.json`、`stage-zero-content-audit.json`、`stage-zero-tool-check.log` 和 Surefire 报告。
- 生产 Java、YAML、数据库、Android、数字人服务均未修改；当前 API 契约保持原样。
- 不更改用户已有未提交文件。

第 0 步完成。下一步为主界面与静默预连接改造，等待用户确认；尚未交付课程选择或自然语言设置功能。

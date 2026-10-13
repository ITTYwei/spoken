# FlashHead 文本流实施与验证（2026-10-13）

当前方案已接通：Java Agent 调用语言模型，case 编排回复轮次，conversation 管理播报生命周期，OpenTalking 接收文本增量并复用切句、TTS、音频分块和数字人渲染。当前渲染器为 **FlashHead Lite**，语言模型配置为 **bonsai-2-27b**。两者是独立配置。

## 职责与执行路径

```text
手机文本 / 语音输入
        |
ConversationCaseImpl（创建轮次、准备上下文、取消旧轮次）
        |
Agent / IChatService（ADK SSE -> Spring AI 文本流；取消模型请求）
        |
conversation.streamReply（当前 replyId、播放订阅、事件与历史）
        |
OpenTalkingAvatarDriver -> OpenTalkingGateway -> OpenTalkingTextSocket
        | begin / append / end / cancel，串行 ACK、有界背压
OpenTalking unified /sessions/{providerSessionId}/text_stream
        |
外部文本队列 -> 原有切句 -> TTS -> 音频块 -> FlashHead / FlashTalk
        |
原有会话的音视频轨道 / WebRTC -> 手机
```

每轮使用一个文本 WebSocket；同一会话的 renderer 和 WebRTC 连接持续复用。没有逐片段调用 `/say`。第一句播放期间可以准备后续句子。取消覆盖模型订阅、文本轮次、TTS 子任务和待播媒体；旧轮次事件在 conversation 中被过滤，避免污染新轮次和历史。

## 切换模式要修改的内容

| 配置位置 | 当前值 / 作用 | 切换要求 |
| --- | --- | --- |
| `spoken-server-app/src/main/resources/application-dev.yml` 的 `spoken-ai.provider.opentalking.default-model` | `flashhead` | 新建会话的默认渲染器；显式选择仍可覆盖默认值 |
| 同文件 `expose-models` | `flashhead`、`flashtalk` | 允许暴露的模式；列出模式不表示对应后端已经在线 |
| 同文件 `spoken-ai.reply.streaming-enabled` | `true` | `false` 回退为收集整段 Agent 回复后调用一次 `/say` |
| `spoken-server-app/src/main/resources/agent/spoken-server.yml` | `bonsai-2-27b`、`reasoning-effort: none` | 只影响语言模型；切换数字人不需要同步换 LLM |
| 服务器 OpenTalking `.env` | `OPENTALKING_DEFAULT_MODEL=flashhead` | OpenTalking 默认值和对应 renderer WebSocket 地址需要匹配实际服务 |
| `/home/nm/develop/code/start_flashtalk_stack.sh` | 默认启动 FlashHead，Lite；默认不启动 Fast-FlashTalk | 启动相应 GPU 服务并确认健康状态后再开放该模式 |
| 手机创建会话 | 当前默认选择 `flashhead` | 切换模式应关闭并重新创建会话；本版不支持对话中途无缝更换 renderer |

Java 与 OpenTalking 统一文本协议不随渲染模式改变。本次没有修改或重装 Android APK。

## 用户本次 Agent 修改的审查结果

- `AiAgentConfigTableVO` 增加 `reasoningEffort`、`logLlmPayload`，属性注释齐全；`ChatModelNode` 在非空时写入模型默认 options，留空时不下发。
- `AgentNode` 将请求日志开关交给每个 Agent 的 `MySpringAI` 实例，相关日志测试通过。
- 修正 `MySpringAI` 原有内部 `subscribe` 与无界 BUFFER 包装，改为组合 Publisher，让需求和取消传到 Spring AI 模型流；增加取消传播验证。
- 修正 `AgentNode` 传递真实配置模型名，实际日志显示 `bonsai-2-27b`，不再使用从实现类推导的 `openai`。
- `ChatService` 显式开启 ADK `RunConfig.StreamingMode.SSE`，避免只有流接口、实际仍等整段结果。

审查重点是上述新增配置与当前流式链路，没有把仓库其他既有数据库、DDD、开场策略改动认定为本次全部重新验收。

## 协议与结束语义

详细帧示例及部署文件见 [文本流部署说明](../dev-ops/text-stream/README.md)。当前仅支持 **unified API 中持有 session runner 的部署方式**；API 与 worker 分进程的 Redis 方案尚未增加跨进程文本流传输。

`replyId` 在同一会话内不可复用，`seq` 从 begin 的 0 严格递增。同内容重复帧确认但不再次入队，不同内容重复、乱序和旧轮次被拒绝。Java 预取 16 个文本片段且每次只有一个待确认帧；服务端文本队列 16、切句队列 4、音频队列 8。追加片段最多 2048 字符，单轮最多 32768 字符。

三个时刻分别记录：

1. `assistant.text_done`：Java 已收完模型文本，不表示语音结束。
2. `speech.media_started` / Java `tts.start`：媒体开始入播放管线；任务启动事件 `speech.started` 不再被当成首播。
3. `playback_end`（`scope=server_media_drained`）与 `speech.ended`：服务器音视频队列已排空并发送，不表示手机扬声器缓冲已播放完。手机还没有单独的播放完成回执。

连接断开或异常取消当前轮次，不提供断线续传。HTTP interrupt 在 unified 模式直接等待 runner 中断，避免异步中断消息迟到后把下一轮停掉。旧 WebSocket 的清理逻辑也检查轮次身份，不能取消新轮次。

## 自动化与真实验证

| 验证 | 结果 |
| --- | --- |
| Java 定向测试 | 23 个，0 失败 / 错误 / 跳过；涵盖流消费、模型取消、准备阶段取消、旧事件隔离、整段回退和用户新增配置 / 日志 |
| Python 文本协议测试 | 5 个通过；涵盖顺序、重复冲突、取消和队列背压 |
| 构建 | Maven 离线使用 `D:\develop\env\mvn_repo`；最终编译成功 |
| OpenTalking 补丁 | 修改文件语法检查通过；在独立备份夹连续执行两次，结果哈希一致 |
| 真实 Agent SSE | 首个数据 1.321 秒，总计 1.589 秒，9 组到达数据 |
| 真实 WebRTC 接收 | 收到 265 个视频帧、379 个音频帧；音视频 PTS 倒退均为 0 |
| 先播后文本结束 | 从 begin 到首个非静音音频 1.395 秒；音频比发送 end 早 1.201 秒 |
| HTTP 打断后立即续播 | 中断请求约 320 毫秒返回，新轮次正常完成 |
| 手机接入 | Xiaomi MI 8 / ADB / 原 APK / FlashHead 会话，2 轮文本输入完整播报，1 轮媒体开始后打断，随后继续回复；最新历史归属正确 |

“先播后文本结束”验证使用真实 TTS、渲染、WebRTC 接收，文本生产方故意延迟补充后续句子，先确认收到非静音音频才发送剩余文本。它证明管线支持边追加边播；不代表每个短回复都能在 LLM 完成之前发声。此次手机短回复的模型生成约 1 秒完成，TTS 首播在其后，属于实际耗时关系。

FlashHead JPEG 批次解码移到线程执行，避免 CPU 解码阻塞 RTC 事件循环。本次单次对比：接收最大间隔由视频约 245 毫秒 / 音频 227 毫秒，降至 202 / 182 毫秒；该结果不能保证所有网络与手机环境无卡顿。

验证产物在 `spoken-server-app/target/`：`text-stream-final-tests.log`、`text-stream-final-compile.log`、`agent-stream-real.json`、`stream-report.json`、`stream-report-before-decode.json`、`java-phone-stream-report.json`、`text-stream-final-phone.png`。这些是本次运行产物，不保证长期保留。服务器原文件与运行报告备份在 `/home/nm/develop/code/backup/flashhead-streaming-20261013`。

## 当前边界与清理

- 尚需手机人工验收长回复句间听感、画面跳变、口型同步、弱网抖动以及真实语音连续对话；帧数和时间戳不能代替主观验收。
- FlashTalk 走相同接口和通用 synthesis 管线，但本次没有重新启动 14B 后端进行效果验收；当前 `/models` 中它不可连接，FlashHead 可连接且为默认。
- 本次启动的 Java 已关闭，测试端口 8092 不再监听；已有 8091 服务未停止。手机 ADB 映射已恢复到 8091，测试会话已关闭。

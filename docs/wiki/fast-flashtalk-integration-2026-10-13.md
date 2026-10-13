# Fast-FlashTalk 接入 flashtalk 模式（2026-10-13）

> 本文记录首次接入的配置和测量。默认尺寸、采样步数、驻留量及性能已在随后更新，当前参数与验证结果见 [单卡优化报告](fast-flashtalk-optimization-2026-10-13.md)。

用户选择将 Fast-FlashTalk 接入 OpenTalking 的 `flashtalk` 模式。接入已完成，服务继续运行，默认模式已切换；实际音频、画面、WebRTC 和中断后再次发言均已验证。

## 当前链路

```text
Java Agent -> 完整回复 -> case 编排 -> OpenTalking /say
                                      |
                                DashScope / Cherry
                                      |
                              16 kHz int16 PCM 块
                                      |
OpenTalking FlashTalkWSClient --AUDI--> Fast-FlashTalk WS adapter
                                      |
                       8 秒音频历史 -> generate_chunk()
                                      |
OpenTalking JPEG decoder     <--VIDX-- 28 张 JPEG
             |
       音视频队列 -> WebRTC -> 播放端
```

这次接入的是音频分块到画面分块的服务层。Java Agent 的 LLM 回复仍完整收集后再调用 `/say`，没有在本次切换中改为外部 LLM token 流式输入。

## 模型与配置

- Fast-FlashTalk 源码提交保持 `6a9d226c8a9864834f5c7110b8ac23a77cc94804`。
- 仍使用 SoulX-FlashTalk-14B 权重，以 A8W8 INT8 量化运行；不是小参数模型。
- 服务：`127.0.0.1:18766`，OpenTalking 通过 `ws://127.0.0.1:18766/flashtalk` 直连。
- 默认模式：`flashtalk`；后端：`direct_ws`；实现标识：`fast`。
- 固定生成参数：448×768、25 FPS、33 帧包含 5 帧运动历史，每块输出 28 帧；对应 17920 个 PCM 采样点（1.12 秒）。去噪为 4 步。
- GPU 驻留参数上限为 80 亿。切换时停止了原 FlashHead 渲染进程，其他 GPU 服务保留。
- 沿用独立 `deps` 和编译缓存，没有安装到生产虚拟环境；逐层量化后搬回 CPU 的兼容补丁继续启用。

远程修改位置：

| 文件 | 变更 |
| --- | --- |
| `experiments/fast-flashtalk-20261012/ws_server.py` | 新增兼容 FlashTalk 协议的服务 |
| `experiments/fast-flashtalk-20261012/start_fast_flashtalk.sh` | 隔离依赖、模型权重、量化补丁和编译缓存的启动入口 |
| `start_flashtalk_stack.sh` | 默认启动 Fast-FlashTalk，默认关闭 FlashHead；精确 PID 停止新后端 |
| `opentalking-main/.env` | 默认模型和直连参数切到 flashtalk/Fast-FlashTalk |
| `opentalking-main/configs/default.yaml` | 固化 `backend: direct_ws`、`implementation: fast`、WS 地址、4 步配置 |
| `opentalking-main/opentalking/pipeline/speak/synthesis_runner.py` | 识别 Fast 后端，跳过动态待机生成，使用本地参考图待机 |

实现标识同时支持环境变量覆盖和持久模型配置。已移除 API 进程中的临时实现/后端/WS 地址环境变量再重启，确认 YAML/.env 配置仍可正确选中 Fast-FlashTalk，并创建就绪会话。

本地 Java 配置与界面元数据也同步修改：默认模型和白名单均为 `flashtalk`，名称为 `Fast-FlashTalk 14B（INT8）`，每块粗略耗时提示改为预热实测约 14 秒。Java 所用 `/say` 请求显式传入 TTS 引擎和音色；不能只在创建会话时传入引擎，然后期待不含 TTS 参数的 `/say` 自动复用。

## 会话隔离与中断

服务持有一个模型实例，以会话租约覆盖初始化、发言和关闭。所有 GPU 操作在一个执行线程运行，事件循环仍可以接收连接和处理 WebSocket 关闭。

活动会话存在时，新会话的 init 返回 `model_busy`；头像和运动状态不会被覆盖。旧连接已经关闭但 GPU 块尚未结束时，重连 init 会等待旧操作完成并清理后再接管。等待上限 120 秒。异常 GPU 调用会结束当前会话，要求重新 init，避免继续使用部分更新的运动状态。

中断不能直接安全终止已提交的 CUDA 运算：OpenTalking 清除播放队列，新连接须等待旧块完成后重建。纯零音频返回静态参考图，不触发去噪；OpenTalking 的动态待机生成被跳过，因此待机不会与发言争用模型。

## 验证结果

### 协议与真实模型

实际使用 OpenTalking 的 `FlashTalkWSClient` 完成握手、连续两块语音、静音、关闭以及换头像初始化。握手返回 `implementation=fast-flashtalk`，画面通过真实 JPEG 解码得到：

| 测试 | 结果 |
| --- | --- |
| 第一块语音，包含首次编译 | 28 帧，37.850 秒 |
| 第二块语音，预热 | 28 帧，14.070 秒 |
| 静音块 | 28 帧，0.095 秒（客户端含传输与 JPEG 解码） |
| 关闭并换另一头像 | 成功初始化，静态帧与上一头像不同 |

14.07 秒生成 1.12 秒内容，当前配置仍不能实时播放。第一块和第二块之间存在编译与缓存差异，不能将上述值解释为稳定首帧延迟。

4 项真实 WebSocket 协议回归测试通过：正常音视频握手/关闭、坏输入与重复 init、初始化失败释放租约、断开时排空推理后由新头像安全接管。模型计算在这些回归测试中用可控屏障替代，GPU 输出另外通过上述真实模型测试验证。

### OpenTalking → TTS → WebRTC

使用服务端广告的默认模式创建 `anchor` 会话，显式选择 DashScope/Cherry。建立真实接收端的 WebRTC 连接后，发言、中断正在执行的 GPU 块、重新发言：

| 指标 | 实测 |
| --- | ---: |
| 会话 worker 就绪 | 2.937 秒 |
| WebRTC 视频 | 收到 1930 帧，448×768，包含待机帧 |
| WebRTC 音频 | 收到 216 个 AudioFrame，峰值 14717，确认非静音 |
| 首句 `/say` 到 `speech.media_started` | 38.669 秒，包含本进程首次推理/编译 |
| 中断 HTTP 返回 | 0.001 秒，表示接收中断请求 |
| 等待旧推理完成并重建至 `speech.ended` | 18.333 秒 |
| 中断后再次 `/say` 到 `speech.media_started` | 15.839 秒 |
| 中断后再次发言 | 成功，收到媒体开始与结束事件 |

`speech.media_started` 是服务端入播放队列事件，不能代替手机端首帧时间。此次 WebRTC 接收端运行在服务器上，确认音视频实际经过 WebRTC，但没有测试手机网络和播放界面。

完整测试会话 `sess_deae76826a98` 已 DELETE，随后 GET 确认为 `closed`。移除临时路由环境变量、重启 API 后的验证会话 `sess_d3feba58a0c9` 也已关闭。早期脚本探测会话 `sess_c03cf97ab795` 和 `sess_b9281b1aac55` 已关闭。

本地使用 `D:\develop\env\mvn_repo` 离线编译全部依赖模块成功；编译日志位于 `spoken-server-app/target/fast-flashtalk-integration-compile.log`。Python 源码 AST 校验通过；启动脚本 `bash -n` 通过。本轮没有启动 Java 服务；本机 8091 未监听，Java 配置与名称将在下次启动 Java 服务时生效。

## 运行与产物

```bash
# 默认启动 Fast-FlashTalk + 既有代理，跳过 FlashHead
bash /home/nm/develop/code/start_flashtalk_stack.sh start

# 查询当前端口状态
bash /home/nm/develop/code/start_flashtalk_stack.sh status

# 停止 Fast-FlashTalk 渲染服务
bash /home/nm/develop/code/start_flashtalk_stack.sh stop-flashtalk
```

不要同时启动旧 SoulX 服务和 Fast 服务占用同一个 18766 端口。原版后端入口保留为显式 `FLASHTALK_IMPLEMENTATION=soulx`；API 的实现标识和对应模式参数也须配套恢复，不能只改启动器。

最终运行：Fast-FlashTalk PID `2404479`；OpenTalking API PID `2482640`。结束采样时，其他 GPU 服务 PID `1078888` 保留；FlashHead PID `1053316` 已不存在。模型关闭会话时卸载 GPU 权重，进程保留以服务后续会话。

服务器产物目录：`/home/nm/develop/code/experiments/fast-flashtalk-20261012/integration-20261013/`：

```text
direct-verification.json          # 客户端协议和实际生成测量
opentalking-verification.json     # SSE、收到的 WebRTC 音视频与中断结果
restart-verification.json         # 无临时路由环境变量的重启验证
chunk-0-first-frame.png           # 实际模型首批画面
chunk-1-first-frame.png           # 连续第二批画面
webrtc-speech-frame.png           # 接收端解码画面
```

服务日志：`/home/nm/develop/code/logs/fast-flashtalk-ws.log`。配置备份：`/home/nm/develop/code/backup/fast-flashtalk-integration-20261013-100006`，目录 700、文件 600；含凭据的 `.env` 备份不应公开。

本地 `docs/dev-ops/fast-flashtalk/` 保存服务、启动脚本、验证脚本，以及 `stack-integration.patch`、`opentalking-idle-integration.patch`、`opentalking-config-integration.patch`。这些补丁展示远程改动，不包含 `.env` 凭据。

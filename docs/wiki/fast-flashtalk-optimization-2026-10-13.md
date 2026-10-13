# Fast-FlashTalk 单卡优化验证（2026-10-13）

默认模式保留 `flashtalk`，后端仍为 Fast-FlashTalk INT8。按提高 GPU 驻留量、减少采样步数、降低分辨率的顺序完成实际模型测试。最终采用 **192×320、2 步、25 FPS** 的速度优先配置；真实客户端连续生成 20 块，预热块平均 **0.830 秒**，每块对应 **1.12 秒** 视频。首次编译、会话初始化及中断恢复仍有等待。

## FlashHead 与 FlashTalk 的取舍

| 实现 | 优点 | 限制 |
| --- | --- | --- |
| FlashHead Lite 1.3B | 面向实时与并发；官方单张 RTX 4090 报告 96 FPS；本服务器之前实测 24 帧约 0.35 秒 | Lite 优先速度，生成质量需要与 Pro、FlashTalk 用相同素材比较 |
| FlashHead Pro 1.3B | 提供更高质量的生成选择 | 官方单张 4090 为 10.8 FPS；25+ FPS 使用两张 5090，不能把 Lite 的速度套给 Pro |
| SoulX-FlashTalk 14B / Fast-FlashTalk | 面向高质量生成；Fast 增加 A8W8 INT8、参数调度和算子优化，可在单卡运行 | 模型规模仍为 14B；原版的 32 FPS 指标使用 8 张 H800；单张消费卡必须实际验证 |

来源：[FlashHead 官方项目](https://github.com/Soul-AILab/SoulX-FlashHead)、[FlashTalk 官方项目](https://github.com/Soul-AILab/SoulX-FlashTalk)、[Fast-FlashTalk 实现](https://github.com/di-osc/fast-flashtalk)。这些指标来自不同硬件与配置，不构成同条件画质或速度排名。

## 测试方法与硬件

- GPU：RTX 5090 D，32607 MiB；PyTorch 2.10.0+cu128。
- 其他 GPU 服务 PID `1078888` 始终保留，占用约 4058 MiB。
- Fast 源码版本保持 `6a9d226c8a9864834f5c7110b8ac23a77cc94804`，继续复用原有权重、独立依赖目录与编译缓存。
- 使用相同 `anchor` 头像、3.36 秒的 16 kHz 单声道音频、相同提示词与随机种子。
- 每项生成三块；首块单列，第二、第三块计算预热均值。计时包含音频特征、GPU 生成、颜色处理及 JPEG 编码，不包含导出 MP4。
- 驻留量扫描复用一个量化模型实例，按上游包装顺序和参数计数调整每层的驻留设备。基准先断言策略与原 80 亿上限一致；最终另启生产实例完成协议验证。
- GPU 驻留上限的计数来自量化包装后的参数元素，**200 亿调度上限不代表模型变为 20B**。本轮包装总计数约 188.84 亿，最终覆盖全部包装层。

## 单变量优化结果

| 配置 | 预热每块耗时 | 生成 FPS | 生成耗时 / 播放时长 | PyTorch 峰值保留显存 |
| --- | ---: | ---: | ---: | ---: |
| 原配置：驻留上限 80 亿，4 步，448×768 | 13.755 秒 | 2.036 | 12.281 | 14088 MiB |
| 驻留上限 120 亿，其他不变 | 11.614 秒 | 2.411 | 10.370 | 17790 MiB |
| 驻留上限 160 亿，其他不变 | 9.682 秒 | 2.892 | 8.645 | 21134 MiB |
| 驻留上限 200 亿，其他不变 | 8.556 秒 | 3.272 | 7.640 | 22936 MiB |
| 驻留上限 200 亿，2 步，448×768 | 4.982 秒 | 5.620 | 4.448 | 22936 MiB |
| 同上，320×576 | 2.507 秒 | 11.169 | 2.238 | 20858 MiB |
| 同上，256×448 | 1.504 秒 | 18.611 | 1.343 | 19908 MiB |
| 另起实例复测 256×448 | 1.518 秒 | 18.445 | 1.355 | 19908 MiB |
| 另起实例测试 192×320 | **0.801 秒** | **34.956** | **0.715** | 19268 MiB |

耗时 / 播放时长小于 1 才能持续供给视频。320×576、256×448 均未达到实时线，因此最终选择 192×320。初轮系统 GPU 总占用采样峰值 28446 MiB，含其他服务；PyTorch 显存统计只包含测试进程。没有发生 OOM 或资源保护终止。

降低尺寸明显减少面部和背景细节，手机全屏放大会模糊。已查看同一时间点的四步、两步及低分辨率测试帧；最终配置多个帧能观察到嘴部变化，未发现明显图像破损。这不能替代完整的人物一致性、口型或画质评分。

## 持久配置与实际服务

```text
OpenTalking default_model = flashtalk
FlashTalk backend        = direct_ws
implementation           = fast
WS                        = ws://127.0.0.1:18766/flashtalk
resident_limit            = 20000000000
sample_steps              = 2
width x height            = 192 x 320
frame_num / motion        = 33 / 5
slice_len / fps           = 28 / 25
sample_rate / PCM samples = 16000 / 17920
```

`ws_server.py` 新增启动参数 `--sample-steps`、`--width`、`--height`；尺寸须为 128～1536 内的 64 倍数，启动时拒绝无效尺寸。握手仍返回实际尺寸和每块采样数。启动脚本固化上述默认值，并保留环境变量覆盖。

OpenTalking `.env` 和 `configs/default.yaml` 同步更新采样步数和尺寸；重启 API 时清除继承环境中的 `OPENTALKING_FLASHTALK_*` 临时覆盖，使其读取持久配置。API `/models` 最终返回默认 `flashtalk`、`connected=true`、`backend=direct_ws`。

服务 PID：Fast 渲染器 `2767310`；OpenTalking API `2786011`。测试完成后 Fast 进程约占 1496 MiB，关闭会话时卸载 DiT；后续新会话需要重新搬入权重。其他 GPU 服务保持运行，FlashHead 没有重新启动。

## 最终验证

### 连续 20 块真实协议

使用 OpenTalking 的 `FlashTalkWSClient`，循环已有音频测持续吞吐，不能将循环素材当作真实对话或画质评测。

- 握手尺寸 192×320，实际每块解码 28 张 JPEG。
- 排除首块，19 块平均 **0.830 秒**，最大 **0.855 秒**，P95 **0.837 秒**；全部低于 1.12 秒。
- 全新服务首次头像初始化 **10.880 秒**，首次 GPU 块 **25.181 秒**，包含本进程首次编译；不能把预热均值解释为冷启动首句延迟。
- 静音块 0.022 秒；关闭后换头像初始化成功，静态帧与原头像不同。

### TTS → Fast → WebRTC

默认模式创建 `anchor` 会话，显式使用 DashScope/Cherry，建立真实接收端，完成发言、推理时中断、再次发言：

- 会话 worker 就绪 2.397 秒。
- 收到 454 个视频帧（含待机帧），192×320；140 个音频帧，非静音峰值 11526。
- 首次 `/say` 到服务端 `speech.media_started` **4.596 秒**。
- 中断 HTTP 接收 0.001 秒；等待旧块清理与重建 **6.010 秒**，随后再次发言成功。
- 重建后 `/say` 到 `speech.media_started` **4.107 秒**。
- 这些是服务端媒体入队时间，不代表手机首帧；LLM 没有参与该脚本轨测试。

### ADB 真机与清理

小米 8 客户端通过独立 Java 8092 后端进入会话，显示 `anchor · flashtalk · 192x320`，中文开场“你好，我是 Amy。你今天做了什么？”完成，之后会话为 READY。手机开场三块服务端生成分别为 **3.288 / 0.794 / 0.793 秒**；后两块入队前视频队列仍分别有 6、14 帧，音频队列有 14、30 个分片，说明这次短句没有因生成跟不上而耗尽队列。

真机截图：`spoken-server-app/target/fast-flashtalk-optimized-phone.png`。客户端没有修改或重新安装，Java 的模型耗时提示改为 900ms。原 8091 Java 进程仍提供旧目录，本轮使用独立端口测试，没有停止它。

WebRTC 会话 `sess_43c314af8640`、真机会话 `sess_d1b5f72b0833` 均确认 `closed`。本次 Java 测试 PID `24268` 已关闭，8092 无监听，ADB 转发恢复至原 8091。Fast 与 OpenTalking 服务保留运行。

4 项 WebSocket 协议回归通过；坏尺寸被启动参数校验拒绝；Python AST 与 shell `bash -n` 通过。使用 `D:\develop\env\mvn_repo` 离线 Maven 编译成功。

## 记录与恢复

服务器结果目录：`/home/nm/develop/code/experiments/fast-flashtalk-20261012/optimization-20261013/`，包含 `matrix.json`、`final-lowres/matrix.json`、两轮 `resources.json`、各配置带音频的 MP4 与抽样 PNG、`deployed-verification/` 真实协议与 WebRTC 记录。

本地不含凭据的 JSON 副本位于 `spoken-server-app/target/fast-flashtalk-optimization/`；编译日志为 `spoken-server-app/target/fast-flashtalk-optimization-compile.log`。可复用的测试程序保存在 `docs/dev-ops/fast-flashtalk/optimize_matrix.py`。

受保护备份位于服务器结果目录的 `backup/`，包括原服务源码、启动脚本、原默认推理参数、API `.env` 和 `default.yaml`。恢复时应配套恢复渲染启动参数及 API 配置后重启相关进程；含凭据的 `.env` 备份不应公开。

本轮解决的是持续视频生成吞吐。Java Agent 仍收齐 LLM 文本后调用 OpenTalking；新进程编译、新会话搬入权重、TTS 等待以及中断后的重建延迟仍需分别优化。

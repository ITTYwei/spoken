# 数字人模型切换验证（2026-10-12）

数字人渲染统一使用 **SoulX-FlashHead-1.3B / Lite**。服务器已有权重及运行服务，本次直接复用。FlashTalk 14B 渲染进程已停止，模型文件保留。本次调整的是数字人渲染模型。

## 配置与运行状态

| 位置 | 调整 / 验证结果 |
| --- | --- |
| 服务器 `/home/nm/develop/code/opentalking-main/.env` | `OPENTALKING_DEFAULT_MODEL=flashhead`，OpenTalking 已重启生效 |
| 服务器 `/home/nm/develop/code/start_flashtalk_stack.sh` | `START_FLASHTALK` 默认值改成 `0`；启动 FlashHead 时显式导出模式，默认 `lite` |
| FlashHead WebSocket | `ws://127.0.0.1:8766/v1/avatar/realtime`，现有进程加载日志确认 `type=lite` |
| OpenTalking `/models` | `default_model=flashhead`；FlashHead 状态为 `connected=true` |
| 原 14B 渲染端口 `18766` | 已关闭；原 GPU 进程已退出 |
| Java `application-dev.yml` 与 `OpenTalkingProperties` | 默认模型保持 `flashhead`，向 App 暴露的模型改为仅 `flashhead`，相关类注释同步更新 |

正常执行 `bash /home/nm/develop/code/start_flashtalk_stack.sh start` 不再默认启动 14B。旧模型的显式启动选项仍保留。

远程配置备份：`/home/nm/develop/code/backup/flashhead-lite-switch-20261012-193031`。备份目录权限为 `700`，备份文件权限为 `600`，避免泄露 `.env` 凭据。

## 实际生成验证

通过 OpenTalking 创建隔离会话，形象 `anchor`、模型 `flashhead`、TTS `dashscope / Cherry`。调用 `/say`，输入：

```text
Hello! What would you like to talk about today?
```

会话就绪耗时约 **0.461 秒**。以发起 `/say` 的时刻为基准，收到以下 SSE 事件：

| 事件 | 耗时 |
| --- | --- |
| `speech.started` | 0.002 秒 |
| `subtitle.chunk` / `speech.media_started` | 1.733 秒 |
| `speech.ended` | 2.498 秒 |

渲染服务实际生成 **4 批、共 96 帧**。每批 24 帧，分别耗时 **0.35 / 0.34 / 0.35 / 0.35 秒**；音频批次为 15360 个采样点，采样率 16000 Hz，视频目标帧率 25 FPS。

这些是服务端脚本轨的生成与事件耗时；本次未连接手机 WebRTC 播放，也未经过 LLM，不能据此推定手机首帧时间或完整聊天延迟。

测试会话 `sess_2657e410667a` 已调用 DELETE 关闭，HTTP 200，随后查询确认 `state=closed`。首次探测会话 `sess_c9b07e515952` 也已关闭。

停止 14B 前 GPU 总占用为 **27955 MiB**，测试后为 **13632 MiB**，释放约 **14 GiB**。FlashHead 进程占用约 **9560 MiB**，另一 GPU 服务保持运行。

机器可读验证记录：服务器 `/home/nm/develop/code/logs/flashhead-lite-switch-verification.json`。`bash -n` 检查启动脚本通过。

本地使用 `D:\develop\env\mvn_repo` 离线执行 `mvn -DskipTests -pl spoken-server-app -am compile`，所有模块编译成功。编译日志位于 `spoken-server-app/target/flashhead-lite-switch-compile.log`。

Java 的模型列表配置需下次启动 Java 服务后生效。本次未启动 Java 服务，轻量数字人和 OpenTalking 服务继续运行。

模型项目：[SoulX-FlashHead](https://github.com/Soul-AILab/SoulX-FlashHead)。

# OpenTalking 外部文本流

这些文件用于当前 OpenTalking unified 部署；完整实施与实测记录见 [验证报告](../../wiki/flashhead-text-stream-verification-2026-10-13.md)。

| 文件 | 作用 |
| --- | --- |
| `external_text.py` | WebSocket 协议、轮次互斥、有界队列、取消和清理 |
| `patch_pipeline.py` | 修改已有切句 / TTS / 渲染管线、添加 API 入口，并把 JPEG 解码移出事件循环 |
| `test_external_text.py` | 不需要 GPU 的协议单元测试 |
| `verify_stream.py` | 真实会话 / TTS / WebRTC 收音收画面、中断后续播验证 |
| `verify_teaching.py` | Java Agent 教学编排、拒答/求助/跟读四轮及 WebRTC 收帧验证 |
| `verify_opening.py` | 首次介绍、无发言再次进入、真实待聊续聊、重复开场幂等及 WebRTC 验证 |

## 协议

连接 `ws://<OpenTalking>/sessions/<providerSessionId>/text_stream`。一个连接对应一轮回复；创建会话时先选择 `flashhead` 或 `flashtalk`，并等待 renderer 就绪。

```json
{"type":"begin","replyId":"reply-unique-uuid","seq":0,"voice":"","ttsProvider":""}
{"type":"append","replyId":"reply-unique-uuid","seq":1,"text":"Hello. "}
{"type":"append","replyId":"reply-unique-uuid","seq":2,"text":"What would you like to talk about?"}
{"type":"end","replyId":"reply-unique-uuid","seq":3}
```

服务器逐条回 `ack`，包含同一个 `replyId`、`seq` 和状态。等待 ACK 后再发下一帧；`end` 只表示没有更多文本。服务器发送 `playback_end`、`scope=server_media_drained` 后可正常关闭连接，这不是手机实际播放完成回执。

打断使用下一序号的 `cancel`，或者既有 HTTP interrupt。异常断线也取消当前轮次。`cancel` 不排在文本队列之后，但仍校验轮次与序号；连接清理会确保旧连接不能中断新轮次。

限制：队列 16，片段 1～2048 字符，单轮最多 32768 字符，append/end/cancel 序号 1～4096，replyId 长度 1～128；同会话已使用 replyId 最多 4096 个。接受过的同内容重复帧不会再次入队；内容冲突、乱序、旧轮次、超时等返回 error 并清理，不提供重连续传。

## 应用与验证

补丁依赖当前已验证的 OpenTalking 源码形状，包含锚点断言。**先备份目标源码和配置，在备份副本演练，再在维护窗口应用并重启 unified API。** 脚本不是事务式更新：若锚点不匹配，应恢复备份并检查版本，不能在部分更新状态下继续启动。

当前目标目录 `/home/nm/develop/code/opentalking-main`。把本目录文件复制到服务器单独的部署目录后执行：

```bash
ROOT=/home/nm/develop/code/opentalking-main
# 将部署目录中的模块复制到既有 speak 包，再应用补丁。
cp external_text.py "$ROOT/opentalking/pipeline/speak/external_text.py"
"$ROOT/.venv/bin/python" patch_pipeline.py "$ROOT"
"$ROOT/.venv/bin/python" -m py_compile \
  "$ROOT/opentalking/pipeline/speak/external_text.py" \
  "$ROOT/opentalking/pipeline/speak/synthesis_runner.py" \
  "$ROOT/opentalking/providers/synthesis/flashhead/ws_client.py" \
  "$ROOT/apps/api/routes/sessions.py"
```

单元测试（本地或服务器部署目录）：

```bash
python -m unittest discover -s . -p test_external_text.py -v
```

真实 WebRTC 验证使用 OpenTalking 虚拟环境运行 `verify_stream.py`，先阅读其中 API 地址和输出路径配置，匹配自己的部署再执行。它会创建并关闭测试会话，验证文本结束前收到非静音音频，以及 interrupt 后的新轮次可播；需要在线 TTS 和 renderer。

`verify_teaching.py` 在服务器的 OpenTalking 虚拟环境运行，依赖 aiohttp、aiortc 和 Python 3.11+。测试前需将临时 Java 8092 通过 SSH 反向转发到服务器 18092；脚本使用独立设备标识，关闭自己创建的会话并保存报告。完成后关闭临时 Java 和 SSH 转发。实施说明见 [教学与上下文验证](../../wiki/teaching-context-memory-verification-2026-10-13.md)。

`verify_opening.py` 使用相同运行环境，默认验证 `flashhead`。GPU 被其他会话占用时，可以设置 `OPENING_TEST_MODEL=mock` 验证接口与持久状态；此时临时 Java 需使用整段回退开关，因为 mock 不支持外部文本流。mock 结果不能作为真实音画验收。实施与边界见 [opening_v2 验证记录](../../wiki/opening-encounter-verification-2026-10-13.md)。

API/worker 分进程方案没有接通此入口的跨进程传输。不要在该部署方式下把接口存在当成流式已可用。Java 回退开关为 `spoken-ai.reply.streaming-enabled: false`，整段模式每轮只调用一次 `/say`。

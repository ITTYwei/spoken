# 本地 Qwen3-TTS 部署验证（2026-10-15）

## 最终配置

- 主机：GPU 服务器 `172.18.0.102`，RTX 5090 D 32607 MiB，驱动 580.82.09。
- 模型：`Qwen/Qwen3-TTS-12Hz-0.6B-CustomVoice`，主模型目录包含 speech tokenizer；另保留独立 tokenizer 下载。
- 模型根目录：`/home/nm/develop/code/models`。
- 推理目录：`/home/nm/develop/code/experiments/qwen3-tts-vllm`。
- 独立 Python 3.12.14，PyTorch 2.11.0+cu130，vLLM / vLLM-Omni 0.20.0。
- 用户级服务：`qwen3-tts.service`，enabled，Linger=yes；启动后实际合成预热，再进入 active。
- 监听：`127.0.0.1:8091`；模型 API 名：`qwen3-tts`。
- 两阶段各 `max_num_seqs=1`、`gpu_memory_utilization=0.12`、`enforce_eager=true`。
- OpenTalking 的 `openai_compatible` provider 使用 `vllm_omni` 协议，流式 PCM 从 24kHz 转成 16kHz。
- 旧场景 Cherry → Vivian、Ethan → Ryan；云端凭据保留，可显式切回 dashscope。
- Java `application-dev.yml` 已改用 `openai_compatible`，需由加载该配置的 Java 进程生效。

复现脚本及操作说明见 [部署目录](../dev-ops/qwen3-tts/README.md)。

## 独立 TTS 实测

| 请求 | 首块 PCM | 总生成耗时 | 音频时长 | RTF |
| --- | ---: | ---: | ---: | ---: |
| 英文首次，含 CUDA 编译 | 87.925 秒 | 88.949 秒 | 6.000 秒 | 14.825 |
| 相同英文预热后 | 0.064 秒 | 0.963 秒 | 5.440 秒 | 0.177 |
| 中文 | 0.061 秒 | 0.945 秒 | 5.600 秒 | 0.169 |

首块时间指 HTTP 客户端收到首块非空 PCM，不代表首个非静音采样或手机实际出声。
RTF 是总生成耗时除以音频长度。不同生成长度来自模型采样，未做同音频对齐的质量评分。
首次启动必须预热；systemd 的 ExecStartPost 已执行真实请求。
基线整卡显存 13116 MiB，完成测试后约 21167 MiB；这是采样值，不是瞬时峰值或硬限额。

## OpenTalking 真实媒体验证

测试选择服务器当前默认 FlashHead Lite，在独立测试会话中通过本地 Qwen TTS 合成，
使用 aiortc 接收实际 WebRTC 音视频，验证中断后再次播报。

- 收到 208 帧视频、199 帧音频，音频峰值 16508，视频 576×576。
- 中断 HTTP 请求及 speech.ended 确认约 0.543 秒。
- 中断后的 `Hello again!` 从 speech.started 到 speech.media_started 约 0.716 秒。
- `post_interrupt_speech_verified=true`，测试会话已关闭。
- 首次数字人会话准备约 37.188 秒，不能用独立 TTS 首包指标代替整个系统等待时间。

系统没有因本任务停止现有 FlashHead 和发音评测服务；OpenTalking API 已重启以加载配置。
独立服务迁移到 systemd 后已再次完成预热并进入 active。
托管重启后，实际 OpenTalking 适配器使用旧 Ethan 标识合成成功，首块约 0.121 秒，总耗时 1.231 秒。

## 回归与记录

- 碎片 HTTP 包、半采样边界、连续重采样状态验证通过。
- OpenTalking 规定的 TTS factory、Edge adapter、试听测试：23 passed。
- 推理 CUDA 张量实际执行通过。
- 服务记录：`journalctl --user -u qwen3-tts.service`。
- 独立音频与测量报告：推理目录的 `samples/`。
- 完整媒体报告：推理目录的 `webrtc/opentalking-verification.json`。
- 原 OpenTalking `.env` 备份：推理目录的 `opentalking.env.before-qwen3`，权限 0600，不提交。
- 原适配器备份：`adapter.py.before-qwen3-vllm`。
- 已补充远端中英文接入说明；文档结构检查仍报告原有英文页面 `docs/api/video-creation-rtmps-execution.md` 缺失，本任务未修改该页面。

暂未做多人并发、长时间稳定性和手机声学延迟测试。回退为手动切换，尚无自动云端故障切换。
OpenTalking 的适配层目前使用 Python 3.11 audioop，升级到 Python 3.13 前需迁移重采样实现。

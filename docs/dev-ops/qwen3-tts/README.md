# Qwen3-TTS 本地推理

用于 spoken-server → OpenTalking → 本地 TTS → FlashHead → WebRTC。
模型为 `Qwen/Qwen3-TTS-12Hz-0.6B-CustomVoice`，推理引擎固定为
vLLM / vLLM-Omni 0.20.0，Linux + Python 3.12。模型和运行依赖不安装进 OpenTalking 环境。

## 安装

将本目录复制到 GPU 服务器的独立工作目录，在该目录执行：

```bash
uv venv --python 3.12 .venv
uv pip install --python .venv/bin/python -r requirements-lock.txt
.venv/bin/python download.py --model-root /srv/models
TTS_MODEL_ROOT=/srv/models bash start.sh
```

`requirements-lock.txt` 是实际部署环境的完整版本清单；`requirements.txt` 记录直接依赖。
部署使用 PyTorch 2.11.0+cu130，需匹配 NVIDIA 驱动，不应覆盖已有数字人环境。
模型目录必须包含完整主模型及其 `speech_tokenizer` 子目录。

启动脚本根据已安装版本自带的 deploy YAML 生成配置，两个阶段均限制为 1 路，
每阶段 `gpu_memory_utilization=0.12`，关闭外层 CUDA Graph 以降低首次配置风险。
该比例不是显存硬上限，仍需测量推理峰值。服务只监听 `127.0.0.1:8091`。
此端口位于 GPU 服务器，与 Windows 上 Java 的 8091 分属不同主机。

## 服务管理

从实际工作目录执行：

```bash
python3 install_service.py --root "$PWD" --model-root /srv/models
systemctl --user daemon-reload
systemctl --user enable --now qwen3-tts.service
systemctl --user status qwen3-tts.service
journalctl --user -u qwen3-tts.service -f
```

`ExecStartPost` 等待 API 并执行真实合成，完成预热后服务才进入 active。
首次 CUDA 编译会很慢；客户端不要仅凭端口开放判定可用。
需要用户的 systemd manager 在注销后继续运行；实际部署主机已启用 Linger。

## OpenTalking 接入

先备份配置，在 OpenTalking 主机运行：

```bash
python3 patch_opentalking.py --repo /path/to/opentalking
```

补丁保留 `.py.before-qwen3-vllm` 原文件，只在 `vllm_omni` 协议下启用真正的音频流式请求，
并将 24kHz PCM 流转换到流水线要求的采样率。转换保留跨包状态和半采样字节。
该适配器使用 Python 3.11 的 `audioop`；若升级到 Python 3.13，需先替换重采样实现。

OpenTalking `.env` 设置：

```dotenv
OPENTALKING_TTS_DEFAULT_PROVIDER=openai_compatible
OPENTALKING_TTS_OPENAI_BASE_URL=http://127.0.0.1:8091/v1
OPENTALKING_TTS_OPENAI_API_KEY=local
OPENTALKING_TTS_OPENAI_MODEL=qwen3-tts
OPENTALKING_TTS_OPENAI_VOICE=ryan
OPENTALKING_TTS_OPENAI_RESPONSE_FORMAT=pcm
OPENTALKING_TTS_OPENAI_PROTOCOL=vllm_omni
```

重启 OpenTalking，使进程环境与 `.env` 一致。Java 的
`spoken-ai.provider.opentalking.default-tts-provider` 同样配置为 `openai_compatible`。
默认场景中的 Cherry 映射到 Vivian，Ethan 映射到 Ryan；这是迁移映射，音色不等同于云端原音色。
9 个原生音色可从推理服务 `/v1/audio/voices` 查询。
实际部署已将其注册到 OpenTalking 的 SQLite 系统音色目录，供首页选择和试听。

## 验证

```bash
.venv/bin/python verify.py --output ./samples
cd /path/to/opentalking
PYTHONPATH=. .venv/bin/python /path/to/tts/test_adapter.py
.venv/bin/python /path/to/tts/verify_webrtc.py --output /tmp/qwen3-webrtc
```

`verify.py` 测量独立 TTS 的首块 PCM、总耗时与音频长度，不等于手机端实际出声延迟。
`verify_webrtc.py` 在 8001 创建自己的 FlashHead 测试会话，验证实际媒体、打断和恢复，最后只关闭自己的会话。
执行前确认单 GPU 会话槽可用。

## 回退

Java 配置改回 `dashscope` 并重启业务服务即可重新显式选择原云端引擎。
若同时回退 OpenTalking 默认值，将 `OPENTALKING_TTS_DEFAULT_PROVIDER` 恢复为原值，重启 API。
原云端凭据及 provider 均保留；当前没有自动故障切换，避免播放中途混入另一种音色。
可使用 `systemctl --user stop qwen3-tts.service` 释放本地 TTS 显存。

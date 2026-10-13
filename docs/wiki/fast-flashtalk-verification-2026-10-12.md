# Fast-FlashTalk 下载与实际视频测试（2026-10-12）

已下载 Fast-FlashTalk 并完成冷、热两轮视频生成。当前与 FlashHead 共用 RTX 5090 D、仅驻留约 20 亿参数的配置，预热后生成 3.36 秒视频需要 53.253 秒，不能满足实时口语陪练。测试完成后进程已退出，生产默认仍为 FlashHead Lite。

## 下载和运行环境

- 项目来源：https://github.com/di-osc/fast-flashtalk
- 源码提交：`6a9d226c8a9864834f5c7110b8ac23a77cc94804`，包版本 0.1.1。
- 独立实验目录：`/home/nm/develop/code/experiments/fast-flashtalk-20261012`。
- 权重复用：`/home/nm/develop/code/models/SoulX-FlashTalk-14B`；wav2vec 复用 `chinese-wav2vec2-base`。Fast-FlashTalk 仍然加载 14B 模型，INT8 量化和 CPU 卸载降低驻留显存，并没有换成小参数模型。
- 使用既有 Python 3.11.16 / PyTorch 2.10.0+cu128 / Triton 3.6.0。额外 41 个依赖全部装进实验目录的 `deps`，通过 `PYTHONPATH` 使用；没有向生产虚拟环境安装包。
- 硬件：RTX 5090 D，显存 32607 MiB。既有 FlashHead 和其他服务预先占用约 13633 MiB，测试期间继续运行。
- 参数：INT8、`num_persistent_param_in_dit=2000000000`、模型/VAE 编译开启、4 步去噪、`audio_encode_mode="stream"`。
- 输入：上游示例 `man.png` 和 `cantonese_16k.wav` 的前 3.36 秒，16 kHz 单声道。输出：448×768、84 帧、播放帧率 25 FPS。

## 实际结果

| 指标 | 第一轮（包含首次编译） | 第二轮（预热） |
| --- | ---: | ---: |
| 从 generate 开始到首块可用 | 78.251 秒 | 18.878 秒 |
| 首块 GPU 生成耗时 | 67.866 秒 | 17.296 秒 |
| 后续两块生成耗时 | 16.907 / 16.850 秒 | 16.772 / 16.732 秒 |
| 整段生成耗时 | 113.495 秒 | 53.253 秒 |
| 实际生成吞吐 | 0.740 FPS | 1.577 FPS |
| PyTorch 峰值已分配显存 | 11087.6 MiB | 7495.4 MiB |
| PyTorch 峰值预留显存 | 11164.0 MiB | 8222.0 MiB |

每块 `generate_chunk()` 返回 33 帧，其中 5 帧为历史运动上下文；实际追加 28 帧，即 1.12 秒视频。预热后约 17 秒生成 1.12 秒内容，无法持续实时播放。MP4 标注的 25 FPS 是播放速率，不能当作生成吞吐。

上述整段耗时包含输入准备和画面生成，不包含 LLM、TTS、网络传输、播放及视频文件导出。模型加载和量化另耗时 84.310 秒。整轮受保护的测试进程耗时 267.1 秒，正常退出码 0；5 秒采样的整卡显存峰值 25394 MiB，主机最低可用内存 28771 MiB。采样峰值不等于瞬时峰值。

## 兼容补丁和测试限制

首次使用原始源码、驻留参数设为 60 亿时，在量化阶段 OOM：它先将量化层聚集到 GPU，之后才进行 CPU 卸载；驻留参数不能限制这个阶段。没有完成画面生成。

为保留现有 FlashHead 服务，只在实验副本的 `gemlite/helper.py` 增加 `FAST_FLASHTALK_QUANT_CPU_STAGING=1`：逐层量化完成即搬回 CPU，并让量化清理阶段保留在 CPU。量化算法及模型权重保持原有逻辑；随后仍由上游显存管理装载推理层。成功的两轮结果来自这份补丁和 20 亿参数驻留设置。

这不是作者标准独占显卡的速度测试，也没有测量更大驻留参数、独占显卡或减少去噪步数后的速度，不能据此断言所有 Fast-FlashTalk 配置都只有 1.58 FPS。可以确定的是：此次实际运行配置不满足实时需求。

## 流式能力核对

对下载提交中的 `src/fast_flashtalk/pipeline.py` 逐行检查：

1. 第 531 行的 `audio_encode_mode` 默认值是 `once`，尽管函数注释声称 `stream` 是默认值。本次显式使用 `stream`。
2. 第 574–576 行先将完整输入音频加载进内存。
3. 第 633–679 行将音频切成块，用 8 秒音频历史窗口提取特征，再调用有状态的 `generate_chunk()`。每块结果追加到列表。
4. 第 685–691 行拼接所有画面，再返回一个完整 `Video`；公开 `generate()` 没有 `yield`，也没有逐块返回给调用者。

因此它具备音频分块编码和画面分块生成核心，但仓库本身没有实现 `LLM token → 分句 → TTS 音频块 → 数字人画面 → WebRTC` 的完整服务链路，也没有现成的外部文本 begin/append/end 接口。`generate_chunk()` 可以作为适配基础，还需处理持续音频输入、画面输出、状态隔离、中断和背压。

当前 OpenTalking 的内部 LLM 路径已有分句/TTS/音频队列/数字人消费编排；Java Agent 路径仍是收集整段回复再调用 `/say`。替换为 Fast-FlashTalk 不会自动改变 Java 这段等待行为，也不能逐 token 重复调用会触发中断的 `/say`。

## 产物和复现材料

临时预览：<http://172.18.0.102:18767/>，可切换 Cold / Warm。该预览服务只展示测试视频和首帧，并设置为启动后一小时自动退出。永久文件保留在服务器实验目录：

```text
cpu-staging-ready/benchmark.json       # 精确性能记录
cpu-staging-ready/resources.json       # 资源采样、命令、退出码
cpu-staging-ready/run.log              # 逐步推理日志
cpu-staging-ready/cold.mp4             # 冷启动视频，含驱动音频
cpu-staging-ready/warm.mp4             # 预热视频，含驱动音频
environment.json                      # 实际解释器及独立依赖版本
source/                               # 下载源码及实验补丁
cache/                                # 独立编译缓存
```

导出检查：两段 MP4 均为 H.264 视频 + AAC 音频，视频、音频各 3.360 秒，视频各 84 帧。`osc-data` 的原始导出没有保留音轨，已使用 ffmpeg 明确映射原始驱动音频，并在基准脚本中补齐这一导出步骤；没有重新生成 GPU 画面。浏览器已确认预热视频加载与播放正常。短示例用于查看画面和口型，不能代替用户头像、多语言和长时间连续对话的质量验证。

本地复现材料位于 `docs/dev-ops/fast-flashtalk/`：`benchmark.py`、`run_guarded.py`、`quant-cpu-staging.patch`、`requirements-isolated-lock.txt`。补丁应用目标是对应提交的 Fast-FlashTalk 源码，运行时须设置该 CPU staging 环境变量。编译缓存和额外依赖目录都放在实验目录，运行时不要读取或输出生产凭据。

结束检查：Fast-FlashTalk GPU 测试 PID 已不存在；GPU 仅保留原先的 FlashHead 和其他服务进程；OpenTalking 与 FlashHead 进程仍在。本轮没有启动 Java 服务，也没有切换生产默认模型。

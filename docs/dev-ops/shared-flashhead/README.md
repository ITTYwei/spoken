# FlashHead 共享权重双用户版本（V2，已部署）

2026-10-15 用户明确授权重启后，已部署至实际 API 8001 / FlashHead 8766，生产端口双用户验收通过。当前 API PID 为 2571463，FlashHead PID 为 2571462。版本一及切换前精确副本保留，可以回滚。

## 实现

- `flashhead_server.py` 只加载一次模型。`SessionContext` 使用独立的 pipeline 外壳，共享生成模型、VAE 和音频编码器；参考图、形状、运动历史、随机生成器、PyTorch CPU/CUDA 随机状态、音频缓冲按会话隔离。
- 模板 pipeline 不准备形象、不生成帧。初始化和音频推理使用同一把公平 asyncio 锁和单线程执行器，按块交错执行；线程工作尚未结束时，取消不能提前释放 GPU 锁。
- 桥接服务默认 `FLASHHEAD_MAX_SESSIONS=2`。超过容量返回明确错误；正常关闭或异常断连释放会话。音频输入需要恰好一块 PCM。
- OpenTalking 通过 `OPENTALKING_FLASHHEAD_MAX_SESSIONS=2` 启用两个 FlashHead 槽位。缺省值为 1，沿用原单槽路径。FlashTalk 保留原单槽实现。API 的容量判定按模型选择。
- 修复排队时非 close 指令移除取消句柄、等待超时留下句柄，以及取消后重复扣队列计数的问题。

当前只验证单张 GPU、FlashHead Lite、单个 unified API 进程。未实现多 API 实例间分布式容量控制，也没有验证三个以上用户或 FlashTalk 与 FlashHead 同时满负载。

## 验证结果

GPU：RTX 5090 D，32GB；沿用原分辨率及采样设置。

| 检查 | 结果 |
| --- | --- |
| 会话状态、权重共享、容量及取消锁安全 | 3 项通过（GPU 服务器 Python 环境） |
| 调度、渲染、FlashHead 客户端及相关回归 | 54 项通过；相关 Ruff 检查通过 |
| API 会话回归 | V1 和 V2 均为 50 通过、10 失败，失败项相同；没有新增失败 |
| 预热后单独生成 vs 双用户交错生成 | A 的 3 块输出 SHA256 完全相同，解码像素平均误差 0 |
| 不同尺寸 | 同时验证 576×576、576×768 的实际 JPEG 帧尺寸 |
| 双用户短时吞吐 | 每人 8.64 秒音频，两路总耗时 6.72 秒；每块 0.96 秒音频，两路平均完成延迟约 0.346 / 0.745 秒 |
| 双客户端真实 WebRTC | 两端合计收到 802 视频帧、826 音频帧，音频峰值非零 |
| 独立打断与退出 | 打断 A 后 B 正常完成原文本；A 再次说话成功；A 退出后 B 再次正常完成 |
| 打断响应 | 本次 HTTP 返回约 1.42 秒，不能视为终端实际停止播放延迟 |
| 队列与容量 | 第三个桥接连接被拒绝；第三个 API 会话排队后可取消；断连后容量可复用 |

首次冷态严格输出比较曾失败；完成不同形状的编译预热后，严格比较通过。输出一致性结论仅适用于已验证的预热样本，不承诺不同编译状态的逐字节一致性。

显存日志：模型加载时 PyTorch allocated 约 4.00 GiB；两个会话初始化后约 4.02 GiB。这个数不包含全部 CUDA/编译/缓存开销。不同形状预热后测试进程的 nvidia-smi 占用约 7.3 GiB，不能将其当作所有形象和推理峰值上限。测试时 V1 与 V2 各有一份模型同时驻留，整卡约 28GB；上线后只保留一份模型。

生产端口复测报告为 `production-dual-webrtc-result.json`：两端合计收到 772 视频帧、815 音频帧，音频峰值非零；独立打断、打断后再说话、A 退出后 B 继续完成文本、第三会话排队取消均通过。打断 HTTP 返回约 0.95 秒。验收结束已关闭本次创建的测试会话，整卡显存随后约 19473 MiB（约 19 GiB），这是测试后读数，不是峰值。

这是短时协议和服务器媒体验收，尚未完成手机双用户听感、持续压力测试或 GPU 上其他服务满载测试。Windows 本地异步测试未完成，已终止；上述测试在服务器实际 Python 环境完成。

## 服务器位置

- 隔离源码及报告：`/home/nm/develop/code/experiments/shared-flashhead-v2/`
- 版本一完整代码备份：`/home/nm/develop/code/backups/version-one-20261015-180327/`
- 精确切换前副本：`/home/nm/develop/code/backups/shared-flashhead-v2-predeploy/`

精确副本中的启动环境包含私有配置，仅保留在服务器受限目录，不复制到仓库。备份不包括模型权重、虚拟环境、数据库或运行中会话。

## 切换和回滚

本次已获明确授权并执行以下部署命令。以后重新部署前需确认当前源码、进程和备份仍匹配，不能直接复用过期 PID：

```bash
cd /home/nm/develop/code/experiments/shared-flashhead-v2
python3 deploy.py install
```

脚本检查待替换文件是否仍等于 prepare 时的内容，正常停止对应进程，替换桥接和调度实现，按模型修补 API，并在 `.env` 写入双会话开关。切换会断开原有连接。若进程身份变化、源码变化或进程未正常退出，脚本会停止，不会强制杀进程。

新 API 日志：`/home/nm/develop/code/logs/api-shared-v2.log`；桥接日志：`/home/nm/develop/code/logs/bridge-shared-v2.log`。FlashHead 完成模型加载和预热后才监听 8766。确认就绪后执行生产端口验收：

```bash
opentalking/.venv/bin/python verify_dual_webrtc.py --port 8001 --output production-dual-webrtc-result.json
```

验收失败时恢复切换前精确副本：

```bash
python3 deploy.py rollback
```

回滚只恢复本次替换的三个源码文件和容量配置项，保留其他配置。会重启 API 与桥接服务。回滚脚本已准备，尚未进行实际停服回滚演练。

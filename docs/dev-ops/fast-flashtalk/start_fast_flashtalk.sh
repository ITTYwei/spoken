#!/usr/bin/env bash
# 功能：在独立依赖目录中运行兼容 OpenTalking 的 Fast-FlashTalk WebSocket 后端。
# 运行过程：导出隔离路径 -> 使用量化 CPU 暂存补丁 -> 加载权重 -> 监听本机端口。
# 案例：bash start_fast_flashtalk.sh，监听 127.0.0.1:18766；由 OpenTalking direct_ws 使用。
# 设计要点：不安装生产依赖；缓存只写入实验目录；由外部启动脚本保存 PID 和日志。
set -euo pipefail

FAST_ROOT="${FAST_FLASHTALK_ROOT:-/home/nm/develop/code/experiments/fast-flashtalk-20261012}"
FAST_PYTHON="${FAST_FLASHTALK_PYTHON:-/home/nm/develop/code/opentalking-main/.venv/bin/python}"
export PYTHONPATH="$FAST_ROOT/deps:$FAST_ROOT/source/src"
export PYTHONDONTWRITEBYTECODE=1
export FAST_FLASHTALK_QUANT_CPU_STAGING=1
export HF_HUB_OFFLINE=1 TRANSFORMERS_OFFLINE=1 TOKENIZERS_PARALLELISM=false
export OMP_NUM_THREADS=8 NUMBA_NUM_THREADS=8
export TORCHINDUCTOR_CACHE_DIR="$FAST_ROOT/cache/inductor"
export TRITON_CACHE_DIR="$FAST_ROOT/cache/triton"
export PYTORCH_CUDA_ALLOC_CONF=expandable_segments:True

exec "$FAST_PYTHON" "$FAST_ROOT/ws_server.py" \
  --host 127.0.0.1 --port "${FLASHTALK_PORT:-18766}" \
  --checkpoint "${FLASHTALK_CKPT_DIR:-/home/nm/develop/code/models/SoulX-FlashTalk-14B}" \
  --wav2vec "${FLASHTALK_WAV2VEC_DIR:-/home/nm/develop/code/models/chinese-wav2vec2-base}" \
  --resident-params "${FAST_FLASHTALK_RESIDENT_PARAMS:-20000000000}" \
  --sample-steps "${FAST_FLASHTALK_SAMPLE_STEPS:-2}" \
  --width "${FAST_FLASHTALK_WIDTH:-192}" \
  --height "${FAST_FLASHTALK_HEIGHT:-320}"

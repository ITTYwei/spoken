#!/usr/bin/env bash
set -euo pipefail
TTS_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
: "${TTS_MODEL_ROOT:?Set TTS_MODEL_ROOT to the model directory}"
TTS_PYTHON="${TTS_PYTHON:-$TTS_ROOT/.venv/bin/python}"
TTS_VLLM="$(dirname -- "$TTS_PYTHON")/vllm"
TTS_PORT="${TTS_PORT:-8091}"
if ss -lntH | awk '{print $4}' | grep -Eq ":${TTS_PORT}$"; then
  echo "Port $TTS_PORT is already occupied" >&2
  exit 1
fi
"$TTS_PYTHON" "$TTS_ROOT/prepare_config.py" --output "$TTS_ROOT/deploy.yaml" \
  --stage-memory "${TTS_STAGE_MEMORY:-0.12}"
export HF_HUB_OFFLINE=1 TRANSFORMERS_OFFLINE=1
export OMP_NUM_THREADS="${OMP_NUM_THREADS:-4}"
echo "$$" > "$TTS_ROOT/server.pid"
exec "$TTS_VLLM" serve "$TTS_MODEL_ROOT/Qwen3-TTS-12Hz-0.6B-CustomVoice" \
  --served-model-name qwen3-tts \
  --omni --deploy-config "$TTS_ROOT/deploy.yaml" \
  --host 127.0.0.1 --port "$TTS_PORT"

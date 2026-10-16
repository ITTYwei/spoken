"""
FlashHead WebSocket 桥接服务
实现 OpenTalking 的 /v1/avatar/realtime 协议

协议:
  控制 (JSON):
    C->S {"type":"session.create","model":"...","inputs":{"image_b64":"...","prompt":"..."},"config":{"seed":9999}}
    S->C {"type":"session.created","session_id":"...","audio":{"sample_rate":16000,"chunk_samples":15360},
          "video":{"fps":25,"width":512,"height":512,"frame_count":24}}
    C->S {"type":"session.close"}  S->C {"type":"session.closed"}
  音频 (二进制):  b"AUDI" + <int16 mono PCM @16kHz>
  视频 (二进制):  b"VIDX" + <uint32 frame_count> + N x (<uint32 jpeg_len> + <jpeg bytes>)
"""
import asyncio
import base64
import concurrent.futures
import json
import os
import struct
import sys
import tempfile
import time
from collections import deque

import numpy as np
import torch
import cv2
import websockets
from PIL import Image
from shared_sessions import SessionContext, SessionRegistry, run_serialized

# ---------------- 配置 ----------------
PROJECT_ROOT = os.environ.get("FLASHHEAD_PROJECT_ROOT", "/home/nm/develop/code/backup/SoulX-FlashHead")
MODEL_DIR = os.environ.get("FLASHHEAD_CKPT_DIR", os.path.join(PROJECT_ROOT, "models/SoulX-FlashHead-1_3B"))
WAV2VEC_DIR = os.environ.get("FLASHHEAD_WAV2VEC_DIR", os.path.join(PROJECT_ROOT, "models/wav2vec2-base-960h"))
MODEL_TYPE = os.environ.get("FLASHHEAD_MODEL_TYPE", "lite")
HOST = os.environ.get("FLASHHEAD_HOST", "0.0.0.0")
PORT = int(os.environ.get("FLASHHEAD_PORT", "8766"))
JPEG_QUALITY = int(os.environ.get("FLASHHEAD_JPEG_QUALITY", "85"))

# ---------------- 按形象自适应分辨率 ----------------
# 规则：量出参考图比例 a，锁短边 S，长边按比例算并量化到 VAE_SPATIAL_STRIDE 的倍数。
#     a <= 1（竖/方）:  W = S,   H = r32(S / a)
#     a >  1（横）:     H = S,   W = r32(S * a)
# 两条兜底：短边不低于 MIN_SHORT_SIDE、latent token 不超预算。
# 置 FLASHHEAD_ADAPTIVE_ASPECT=0 退回 infer_params.yaml 的全局固定分辨率。
ADAPTIVE_ASPECT = os.environ.get("FLASHHEAD_ADAPTIVE_ASPECT", "1").strip().lower() in {"1", "true", "yes", "on"}
TARGET_SHORT_SIDE = int(os.environ.get("FLASHHEAD_TARGET_SHORT_SIDE", "576"))
MIN_SHORT_SIDE = int(os.environ.get("FLASHHEAD_MIN_SHORT_SIDE", "384"))
MAX_LATENT_TOKENS = int(os.environ.get("FLASHHEAD_MAX_LATENT_TOKENS", "800"))
MAX_ASPECT = float(os.environ.get("FLASHHEAD_MAX_ASPECT", "2.5"))
# 与 models/SoulX-FlashHead-1_3B/Model_Lite/config.json 的 vae_stride[1:] 一致
VAE_SPATIAL_STRIDE = 32

sys.path.insert(0, PROJECT_ROOT)
os.chdir(PROJECT_ROOT)

from flash_head.inference import (
    get_pipeline,
    get_base_data,
    get_audio_embedding,
    run_pipeline,
    get_infer_params,
)

MAGIC_AUDIO = b"AUDI"
MAGIC_VIDEO = b"VIDX"

# ---------------- 全局状态 ----------------
pipeline = None
PARAMS = {}
MAX_SESSIONS = int(os.environ.get("FLASHHEAD_MAX_SESSIONS", "2"))
SESSIONS = SessionRegistry(MAX_SESSIONS)
GPU_EXECUTOR = concurrent.futures.ThreadPoolExecutor(max_workers=1, thread_name_prefix="flashhead-gpu")
INFER_LOCK = asyncio.Lock()  # 串行化 GPU 推理 + pipeline 状态变更


def load_pipeline():
    global pipeline, PARAMS
    print("[FlashHead] Loading pipeline (ckpt=%s, type=%s) ..." % (MODEL_DIR, MODEL_TYPE), flush=True)
    t0 = time.time()
    pipeline = get_pipeline(
        world_size=1,
        ckpt_dir=MODEL_DIR,
        model_type=MODEL_TYPE,
        wav2vec_dir=WAV2VEC_DIR,
    )
    p = get_infer_params()
    frame_num = int(p["frame_num"])
    motion = int(p["motion_frames_num"])
    slice_len = frame_num - motion
    sample_rate = int(p["sample_rate"])
    tgt_fps = int(p["tgt_fps"])
    cached_dur = int(p["cached_audio_duration"])

    PARAMS.update(
        frame_num=frame_num,
        motion_frames_num=motion,
        slice_len=slice_len,
        sample_rate=sample_rate,
        tgt_fps=tgt_fps,
        chunk_samples=slice_len * sample_rate // tgt_fps,
        cached_audio_length_sum=sample_rate * cached_dur,
        audio_end_idx=cached_dur * tgt_fps,
        audio_start_idx=cached_dur * tgt_fps - frame_num,
        height=int(p["height"]),
        width=int(p["width"]),
    )
    print(
        "[FlashHead] Pipeline loaded in %.1fs | VRAM %.2f GB | frame_num=%d motion=%d slice=%d "
        "chunk_samples=%d audio_start=%d audio_end=%d %dx%d"
        % (
            time.time() - t0,
            torch.cuda.memory_allocated() / 1024 ** 3,
            frame_num, motion, slice_len,
            PARAMS["chunk_samples"], PARAMS["audio_start_idx"], PARAMS["audio_end_idx"],
            PARAMS["width"], PARAMS["height"],
        ),
        flush=True,
    )


def _b64_to_tempfile(b64_str):
    raw = base64.b64decode(b64_str)
    fd, path = tempfile.mkstemp(suffix=".png", prefix="flashhead_ref_")
    os.close(fd)
    with open(path, "wb") as f:
        f.write(raw)
    return path


def _frames_to_response(frames_np):
    frames_np = np.clip(frames_np, 0, 255).astype(np.uint8)
    parts = []
    for i in range(frames_np.shape[0]):
        bgr = cv2.cvtColor(frames_np[i], cv2.COLOR_RGB2BGR)
        ok, buf = cv2.imencode(".jpg", bgr, [int(cv2.IMWRITE_JPEG_QUALITY), JPEG_QUALITY])
        if not ok:
            raise RuntimeError("JPEG encode failed")
        parts.append(struct.pack("<I", len(buf)) + buf.tobytes())
    header = MAGIC_VIDEO + struct.pack("<I", frames_np.shape[0])
    return header + b"".join(parts)


def _do_infer(audio_array, context):
    """阻塞式推理(在线程池中调用)。返回 (T,H,W,3) uint8 RGB。"""
    emb = get_audio_embedding(
        context.pipeline, audio_array, context.params["audio_start_idx"], context.params["audio_end_idx"]
    )
    video = run_pipeline(context.pipeline, emb)
    video = video[PARAMS["motion_frames_num"]:]
    arr = video.detach().cpu().numpy()
    if arr.ndim == 4 and arr.shape[-1] != 3:
        arr = arr.transpose(0, 2, 3, 1)
    if arr.max() <= 1.0:
        arr = arr * 255.0
    return arr


def _r32(x):
    """就近量化到 VAE 空间步长的倍数（下限一个步长）。"""
    return max(VAE_SPATIAL_STRIDE, int(VAE_SPATIAL_STRIDE * round(float(x) / VAE_SPATIAL_STRIDE)))


def _shape_for_short_side(short, aspect):
    """给定短边和比例，返回 (height, width)，两者都是 VAE_SPATIAL_STRIDE 的倍数。"""
    s = _r32(short)
    if aspect <= 1.0:            # 竖图 / 方图：短边是宽
        return _r32(s / aspect), s
    return s, _r32(s * aspect)   # 横图：短边是高


def _latent_tokens(height, width):
    return (height // VAE_SPATIAL_STRIDE) * (width // VAE_SPATIAL_STRIDE)


def _adaptive_target(ref_path):
    """按参考图比例推导 (height, width)；量不出来就返回 None（退回 yaml 固定值）。"""
    try:
        with Image.open(ref_path) as im:
            src_w, src_h = im.size
        if src_w <= 0 or src_h <= 0:
            return None
    except Exception as exc:  # noqa: BLE001 - 量不出比例就不改变行为
        print("[FlashHead] adaptive: 读不出 %s 的尺寸 (%r)，退回固定分辨率" % (ref_path, exc), flush=True)
        return None

    raw_aspect = src_w / float(src_h)
    # 极端比例钳制：超出的部分交给 prepare_params 里的 center_crop，避免长边把算力吃光
    aspect = min(max(raw_aspect, 1.0 / MAX_ASPECT), MAX_ASPECT)

    short = float(TARGET_SHORT_SIDE)
    short = max(short, float(MIN_SHORT_SIDE))            # 人脸别太小
    while short > MIN_SHORT_SIDE:                        # 兜底：latent token 不超预算
        h, w = _shape_for_short_side(short, aspect)
        if _latent_tokens(h, w) <= MAX_LATENT_TOKENS:
            break
        short -= VAE_SPATIAL_STRIDE

    height, width = _shape_for_short_side(short, aspect)
    if abs(aspect - raw_aspect) > 1e-3:
        print("[FlashHead] adaptive: 源图 %dx%d 比例 %.3f 超出 [%.2f, %.2f]，按 %.3f 处理（两端会被裁）"
              % (src_w, src_h, raw_aspect, 1.0 / MAX_ASPECT, MAX_ASPECT, aspect), flush=True)
    print("[FlashHead] adaptive: 参考图 %dx%d (a=%.4f) -> %dx%d, latent tokens=%d"
          % (src_w, src_h, raw_aspect, width, height, _latent_tokens(height, width)), flush=True)
    return height, width


def _prepare_session(ref_path, context, seed=9999):
    target = _adaptive_target(ref_path) if ADAPTIVE_ASPECT else None
    get_base_data(context.pipeline, cond_image_path_or_dir=ref_path, base_seed=seed,
                  use_face_crop=False, target_size=target)
    if target is not None:
        # session.created 回包读的是 PARAMS，这里同步成真实生效的尺寸
        context.params["height"], context.params["width"] = int(target[0]), int(target[1])


def _session_work(context, function, *args):
    # LTX VAE posterior sampling also consumes the global torch RNG. Preserve
    # per-session CPU/CUDA RNG, as well as pipeline.generator, under the GPU lock.
    device = torch.cuda.current_device()
    with torch.random.fork_rng(devices=[device]):
        if context.cpu_rng is None:
            torch.manual_seed(9999)
        else:
            torch.set_rng_state(context.cpu_rng)
            torch.cuda.set_rng_state(context.cuda_rng, device)
        try:
            return function(*args, context)
        finally:
            context.cpu_rng = torch.get_rng_state()
            context.cuda_rng = torch.cuda.get_rng_state(device)


def _initialize(ref_path, context):
    _prepare_session(ref_path, context)


async def handle_connection(websocket):
    sid = None
    context = None
    ref_path = None
    audio_dq = None
    try:
        async for msg in websocket:
            if isinstance(msg, str):
                data = json.loads(msg)
                mtype = data.get("type")
                if mtype == "session.create":
                    if context is not None:
                        await websocket.send(json.dumps({"type": "error", "message": "session already created"}))
                        continue
                    img_b64 = (data.get("inputs") or {}).get("image_b64") or ""
                    if not img_b64:
                        await websocket.send(json.dumps({"type": "error", "message": "missing inputs.image_b64"}))
                        continue
                    try:
                        sid = SESSIONS.reserve()
                    except RuntimeError:
                        await websocket.send(json.dumps({"type": "error", "message": "session capacity reached"}))
                        await websocket.close(code=1013, reason="session capacity reached")
                        return
                    ref_path = _b64_to_tempfile(img_b64)
                    context = SessionContext.create(pipeline, PARAMS)
                    await run_serialized(INFER_LOCK, GPU_EXECUTOR, _session_work,
                                         context, _initialize, ref_path)
                    params = context.params
                    audio_dq = deque([0.0] * params["cached_audio_length_sum"],
                                     maxlen=params["cached_audio_length_sum"])
                    await websocket.send(json.dumps({
                        "type": "session.created", "session_id": sid,
                        "audio": {"sample_rate": params["sample_rate"],
                                  "chunk_samples": params["chunk_samples"]},
                        "video": {"fps": params["tgt_fps"], "width": params["width"],
                                  "height": params["height"], "frame_count": params["slice_len"],
                                  "motion_frames_num": params["motion_frames_num"]},
                    }))
                    print("[%s] session.created active=%d %dx%d allocated=%.3fGB reserved=%.3fGB" %
                          (sid, len(SESSIONS.sessions), params["width"], params["height"],
                           torch.cuda.memory_allocated()/1024**3,
                           torch.cuda.memory_reserved()/1024**3), flush=True)
                elif mtype == "session.close":
                    await websocket.send(json.dumps({"type": "session.closed"}))
                    break
            elif isinstance(msg, bytes) and msg[:4] == MAGIC_AUDIO:
                if context is None:
                    await websocket.send(json.dumps({"type": "error", "message": "create session first"}))
                    continue
                if len(msg) - 4 != context.params["chunk_samples"] * 2:
                    await websocket.send(json.dumps({"type": "error", "message": "invalid audio chunk size"}))
                    continue
                pcm = np.frombuffer(msg[4:], dtype=np.int16).astype(np.float32) / 32768.0
                audio_dq.extend(pcm.tolist())
                audio_array = np.array(audio_dq)
                t0 = time.monotonic()
                arr = await run_serialized(INFER_LOCK, GPU_EXECUTOR, _session_work,
                                           context, _do_infer, audio_array)
                payload = await asyncio.to_thread(_frames_to_response, arr)
                await websocket.send(payload)
                print("[%s] generated %d frames, payload=%dKB, %.3fs" %
                      (sid, arr.shape[0], len(payload)//1024, time.monotonic()-t0), flush=True)
    except websockets.exceptions.ConnectionClosed:
        pass
    except Exception:
        import traceback
        traceback.print_exc()
        try:
            await websocket.send(json.dumps({"type": "error", "message": "session processing failed"}))
            await websocket.close(code=1011, reason="session processing failed")
        except websockets.exceptions.ConnectionClosed:
            pass
    finally:
        context = None
        if sid is not None:
            SESSIONS.release(sid)
        if ref_path and os.path.exists(ref_path):
            os.unlink(ref_path)
        print("[%s] Disconnected active=%d" % (sid, len(SESSIONS.sessions)), flush=True)


def _warmup_inference():
    """Pay the first-inference CUDA/kernel cost at startup.

    The very first chunk of a session costs ~13s (kernel autotune + lazy init)
    while steady state is ~0.3s per chunk. Doing one throwaway generation before
    the server starts listening keeps that stall out of the first user turn.
    """
    if os.environ.get("FLASHHEAD_WARMUP", "1").strip().lower() in {"0", "false", "no", "off"}:
        print("[FlashHead] Warmup disabled (FLASHHEAD_WARMUP)", flush=True)
        return
    raw = os.environ.get("FLASHHEAD_WARMUP_IMAGES", "").strip() or os.environ.get("FLASHHEAD_WARMUP_IMAGE", "").strip()
    refs = [p.strip() for p in raw.split(",") if p.strip()] or [os.path.join(PROJECT_ROOT, "examples", "girl.png")]
    refs = [p for p in refs if os.path.exists(p)]
    if not refs:
        print("[FlashHead] Warmup skipped: no reference image", flush=True)
        return
    # 自适应分辨率下每个形状首次都要付一次编译成本，所以支持一次预热多个参考图：
    #   FLASHHEAD_WARMUP_IMAGES=/path/a.png,/path/b.png
    for ref in refs:
        try:
            t0 = time.time()
            context = SessionContext.create(pipeline, PARAMS)
            _session_work(context, _initialize, ref)
            # 真实会话里 audio_dq 的长度恒为 cached_audio_length_sum；用低幅噪声而不是
            # 纯静音，避免 wav2vec2 在零能量输入上产生异常张量。
            n = int(PARAMS["cached_audio_length_sum"])
            rng = np.random.default_rng(0)
            audio = (rng.standard_normal(n).astype(np.float32) * 0.01)
            arr = _session_work(context, _do_infer, audio)
            print("[FlashHead] Warmup done in %.2fs (frames=%d, %s)"
                  % (time.time() - t0, arr.shape[0], os.path.basename(ref)), flush=True)
        except Exception as exc:  # noqa: BLE001 - warmup must never block startup
            import traceback
            print("[FlashHead] Warmup failed (non-fatal) for %s: %r" % (ref, exc), flush=True)
            traceback.print_exc()


async def main():
    load_pipeline()
    _warmup_inference()
    print("[FlashHead] Server started: ws://%s:%d/v1/avatar/realtime" % (HOST, PORT), flush=True)
    async with websockets.serve(
        handle_connection, HOST, PORT, max_size=50 * 1024 * 1024, max_queue=2
    ):
        await asyncio.Future()


if __name__ == "__main__":
    asyncio.run(main())

"""Fast-FlashTalk adapter for OpenTalking's init / AUDI / VIDX protocol.

功能：把持续输入的 16 kHz PCM 音频块转换为数字人画面，复用现有 FlashTalk 客户端。
运行过程：
<pre>
init(JSON + image) -> acquire single-model lease -> prepare avatar
AUDI(int16 PCM)    -> rolling audio history -> generate_chunk -> VIDX(JPEGs)
close/disconnect  -> drain GPU work -> clear avatar state -> release lease
</pre>
案例：17920 个采样点（1.12 秒）输入，输出 28 张 JPEG，播放帧率 25 FPS。
设计要点：
<ul>
  <li>单线程执行模型操作，事件循环保持可用；第二个会话收到 busy，而不是覆盖模型状态。</li>
  <li>逐块生成，不调用等待整个音频文件的 generate()，不改变 Java 的 LLM 调用职责。</li>
  <li>断开后等待当前 GPU 操作完成再释放租约；新会话重新初始化运动和音频历史。</li>
  <li>纯静音返回静态参考图，避免空闲画面持续触发昂贵的 14B 推理。</li>
</ul>
"""

import argparse
import asyncio
import base64
import io
import json
import logging
import struct
import tempfile
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import numpy as np
from PIL import Image as PILImage, ImageOps
from websockets.asyncio.server import serve
from websockets.exceptions import ConnectionClosed
from websockets.protocol import State

LOG = logging.getLogger("fast-flashtalk-ws")
MAGIC_AUDIO = b"AUDI"
MAGIC_VIDEO = b"VIDX"


def encode_video(frames, quality=75):
    """Pack RGB frames into the existing little-endian VIDX/JPEG wire format."""
    parts = [MAGIC_VIDEO, struct.pack("<I", len(frames))]
    for frame in frames:
        output = io.BytesIO()
        PILImage.fromarray(np.asarray(frame, dtype=np.uint8)).save(output, "JPEG", quality=quality)
        jpeg = output.getvalue()
        parts.extend((struct.pack("<I", len(jpeg)), jpeg))
    return b"".join(parts)


class FastEngine:
    """功能：管理 Fast-FlashTalk 权重及一个会话的音频、运动上下文。

    运行过程：
    <pre>
    initialize(image) -> prepare_params -> reset 8-second audio history
    render(PCM)       -> append history -> audio embedding -> video chunk
    release()         -> offload DiT -> discard avatar tensors
    </pre>
    案例：两个连续的 17920-sample 音频块，分别产出 28 帧，第二块沿用第一块运动上下文。
    设计要点：
    <ul>
      <li>权重常驻进程，会话上下文只在独占租约内使用。</li>
      <li>输出参数取自实际模型，客户端按握手结果切音频，避免帧数和音频长度不一致。</li>
      <li>参考图空闲画面不修改模型运动张量；下一段发言继续已有运动上下文。</li>
      <li>采样步数和尺寸允许启动时覆盖；降低分辨率时仍由握手告知客户端实际尺寸。</li>
    </ul>
    """

    def __init__(self, checkpoint, wav2vec, resident_params, sample_steps=None, width=None, height=None):
        from fast_flashtalk import FlashTalkPipeline

        # 单个权重实例；调用方须在同一 GPU 执行线程访问。
        self.pipeline = FlashTalkPipeline(
            checkpoint_dir=checkpoint, wav2vec_dir=wav2vec,
            num_persistent_param_in_dit=resident_params,
        )
        # 握手参数与上游默认生成配置保持一致。
        self.config = dict(self.pipeline.infer_params)
        # 运行参数由启动器固化；握手仍返回实际尺寸和帧数，避免客户端音画不同步。
        for name, value in (("sample_steps", sample_steps), ("width", width), ("height", height)):
            if value is not None:
                self.config[name] = value
        # 每个新会话都会重新分配的连续音频历史。
        self.history = None
        # 当前头像的静态空闲帧；关闭会话后清除。
        self.reference = None
        # 已接收音频块数量，用于耗时日志关联。
        self.chunk_index = 0

    def initialize(self, image, prompt, seed):
        from fast_flashtalk import Image

        c = self.config
        # Upstream prepare_params calls Image.load(), which requires a URI even
        # when Image already has pixel data. The file is scoped to initialization.
        with tempfile.TemporaryDirectory(prefix="fast-ft-avatar-") as folder:
            image_path = Path(folder) / "reference.png"
            image.save(image_path, "PNG")
            self.pipeline.prepare_params(
                input_prompt=prompt, cond_image=Image(uri=str(image_path)), seed=seed,
                target_size=(c["height"], c["width"]),
                frame_num=c["frame_num"], motion_frames_num=c["motion_frames_num"],
                sampling_steps=c["sample_steps"], shift=c["sample_shift"],
                color_correction_strength=c["color_correction_strength"],
            )
        self.history = np.zeros(c["sample_rate"] * c["cached_audio_duration"], np.float32)
        self.reference = np.asarray(ImageOps.fit(image.convert("RGB"), (c["width"], c["height"])))
        self.chunk_index = 0

    def render(self, pcm):
        import torch

        c = self.config
        samples = np.frombuffer(pcm, dtype="<i2").astype(np.float32) / 32768.0
        self.history = np.concatenate((self.history[len(samples):], samples))
        slice_len = c["frame_num"] - c["motion_frames_num"]
        start = time.perf_counter()
        mode = "speech"
        if not np.any(samples):
            mode = "static_idle"
            frames = np.repeat(self.reference[None], slice_len, axis=0)
        else:
            end = c["cached_audio_duration"] * c["tgt_fps"]
            embedding = self.pipeline.get_audio_embedding(self.history, end - c["frame_num"], end)
            video = self.pipeline.generate_chunk(embedding)
            torch.cuda.synchronize()
            frames = video[c["motion_frames_num"]:].cpu().numpy().astype(np.uint8)
        payload = encode_video(frames)
        LOG.info("chunk=%d mode=%s frames=%d seconds=%.3f bytes=%d",
                 self.chunk_index, mode, len(frames), time.perf_counter() - start, len(payload))
        self.chunk_index += 1
        return payload

    def release(self):
        self.pipeline.offload_dit_model()
        for name in ("arg_c", "latent_motion_frames", "generator", "cond_image_tensor",
                     "original_color_reference", "timesteps"):
            if hasattr(self.pipeline, name):
                delattr(self.pipeline, name)
        self.history = None
        self.reference = None


class FastFlashTalkService:
    """功能：在 WebSocket 连接与共享模型之间提供独占会话和参数校验。

    运行过程：
    <pre>
    connection -> init validation -> lease -> GPU executor -> init_ok
    audio      -> length validation -> GPU executor -> VIDX
    cleanup    -> executor drain -> lease release
    </pre>
    案例：A 已初始化时，B 的 init 返回 model_busy；A 关闭后 B 可成功初始化。
    设计要点：
    <ul>
      <li>音频长度由 frame_num、motion_frames_num、采样率和 FPS 计算，拒绝截断的 PCM。</li>
      <li>模型状态不能被并发 init 覆盖，关闭及异常路径统一释放租约。</li>
      <li>最大消息、消息队列和写缓冲均有上限，客户端一问一答自然产生背压。</li>
    </ul>
    """

    def __init__(self, engine, executor):
        # 所有连接共享的 GPU 模型实例。
        self.engine = engine
        # 只允许一个线程执行 GPU 状态操作。
        self.executor = executor
        # 覆盖整个头像会话生命周期的独占锁。
        self.lease = asyncio.Lock()
        # 当前持有模型的连接；用于识别中断后旧连接仍在排空 GPU 的情况。
        self.owner = None

    async def run(self, operation, *args):
        future = asyncio.get_running_loop().run_in_executor(self.executor, operation, *args)
        try:
            return await asyncio.shield(future)
        except asyncio.CancelledError:
            # CUDA inference cannot be safely cancelled mid-kernel. Drain before
            # allowing another avatar to own the shared model state.
            await asyncio.shield(future)
            raise

    async def handle(self, websocket):
        active = False

        async def cleanup():
            nonlocal active
            if active:
                try:
                    await self.run(self.engine.release)
                finally:
                    active = False
                    self.owner = None
                    self.lease.release()

        try:
            async for message in websocket:
                try:
                    if isinstance(message, str):
                        command = json.loads(message)
                        if not isinstance(command, dict):
                            raise ValueError("JSON command must be an object")
                        kind = command.get("type")
                        if kind == "close":
                            await cleanup()
                            await websocket.send(json.dumps({"type": "close_ok"}))
                            continue
                        if kind != "init":
                            raise ValueError("Supported commands: init, close")
                        if active:
                            raise ValueError("Session already initialized; close before reinitializing")
                        if self.lease.locked() and self.owner is not None and self.owner.state == State.OPEN:
                            raise ValueError("model_busy: another session owns the Fast-FlashTalk model")
                        raw = base64.b64decode(command.get("ref_image", ""), validate=True)
                        with PILImage.open(io.BytesIO(raw)) as opened:
                            image = opened.convert("RGB")
                        prompt = command.get("prompt") or "A person is talking. The background remains static."
                        if not isinstance(prompt, str) or len(prompt) > 4096:
                            raise ValueError("prompt must be a string of at most 4096 characters")
                        seed = int(command.get("seed", 9999))
                        # OpenTalking closes/reconnects after an interrupt. A
                        # disconnected owner may still be draining a GPU chunk;
                        # wait for it instead of failing the new avatar init.
                        await asyncio.wait_for(self.lease.acquire(), timeout=120)
                        active = True
                        self.owner = websocket
                        try:
                            await self.run(self.engine.initialize, image, prompt, seed)
                        except BaseException:
                            await cleanup()
                            raise
                        c = self.engine.config
                        slice_len = c["frame_num"] - c["motion_frames_num"]
                        await websocket.send(json.dumps({
                            "type": "init_ok", "implementation": "fast-flashtalk",
                            "frame_num": c["frame_num"], "motion_frames_num": c["motion_frames_num"],
                            "slice_len": slice_len, "fps": c["tgt_fps"],
                            "sample_rate": c["sample_rate"], "height": c["height"], "width": c["width"],
                            "chunk_samples": slice_len * c["sample_rate"] // c["tgt_fps"],
                        }))
                        LOG.info("session initialized implementation=fast-flashtalk")
                    else:
                        if not active:
                            raise ValueError("No active session; send init first")
                        c = self.engine.config
                        expected = (c["frame_num"] - c["motion_frames_num"]) * c["sample_rate"] // c["tgt_fps"] * 2
                        if message[:4] != MAGIC_AUDIO or len(message) != expected + 4:
                            raise ValueError(f"Expected AUDI followed by {expected} bytes of int16 PCM")
                        try:
                            payload = await self.run(self.engine.render, message[4:])
                        except Exception:
                            # A failed GPU call may leave partial motion state.
                            # Require a new init rather than reuse that state.
                            await cleanup()
                            raise
                        await websocket.send(payload)
                except ConnectionClosed:
                    raise
                except Exception as error:
                    LOG.warning("request failed: %s", error)
                    await websocket.send(json.dumps({"type": "error", "message": str(error)}))
        except ConnectionClosed:
            pass
        finally:
            await cleanup()
            LOG.info("connection released")


async def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=18766)
    parser.add_argument("--checkpoint", required=True)
    parser.add_argument("--wav2vec", required=True)
    parser.add_argument("--resident-params", type=int, default=8_000_000_000)
    parser.add_argument("--sample-steps", type=int, choices=(2, 4))
    parser.add_argument("--width", type=int)
    parser.add_argument("--height", type=int)
    args = parser.parse_args()
    # VAE 与空间 patch 要求尺寸对齐；启动时拒绝坏参数，避免收到语音后才报错。
    for name in ("width", "height"):
        value = getattr(args, name)
        if value is not None and (value < 128 or value > 1536 or value % 64):
            parser.error(f"--{name} must be a multiple of 64 between 128 and 1536")
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
    with ThreadPoolExecutor(max_workers=1, thread_name_prefix="fast-flashtalk-gpu") as executor:
        engine = await asyncio.get_running_loop().run_in_executor(
            executor, FastEngine, args.checkpoint, args.wav2vec, args.resident_params,
            args.sample_steps, args.width, args.height,
        )
        service = FastFlashTalkService(engine, executor)
        async with serve(service.handle, args.host, args.port, max_size=20 * 1024 * 1024,
                         max_queue=1, write_limit=64 * 1024, ping_interval=20, ping_timeout=180):
            LOG.info("MODEL_READY implementation=fast-flashtalk port=%d resident_params=%d steps=%d size=%dx%d",
                     args.port, args.resident_params, engine.config["sample_steps"],
                     engine.config["width"], engine.config["height"])
            await asyncio.Future()


if __name__ == "__main__":
    asyncio.run(main())

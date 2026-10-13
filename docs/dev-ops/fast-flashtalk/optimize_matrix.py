"""功能：按单变量顺序测量 Fast-FlashTalk 驻留、采样步数与分辨率。

运行过程：<pre>
load INT8 -> baseline -> residency sweep -> 2 steps -> lower resolutions
                   | GPU synchronized timings, memory, JPEG frames, preview
                   v
              matrix.json -> deployment candidate
</pre>
案例：448x768 / 4 步为基准；提高驻留后只改为 2 步，最后降低尺寸。
设计要点：<ul>
<li>使用实际服务的 FastEngine，包含音频特征、GPU 生成与 JPEG 编码。</li>
<li>保持头像、音频、提示词和随机种子一致；第一块与预热块分别统计。</li>
<li>驻留策略与上游包装顺序、参数计数一致；溢出后恢复 CPU 权重。</li>
<li>每项保存视频和分段日志，速度不代表画质已经合格。</li>
</ul>
"""

import argparse
import gc
import io
import json
import statistics
import struct
import subprocess
import time
import wave
from pathlib import Path

import numpy as np
import torch
from PIL import Image

from fast_flashtalk.vram_management import AutoWrappedLinear, AutoWrappedModule
from ws_server import FastEngine


def set_residency(engine, limit, verify_initial=False):
    """功能：复用已量化权重，按上游顺序设置每层的 GPU 常驻策略。"""
    engine.release()
    count = 0
    resident = 0
    wrappers = 0
    for module in engine.pipeline.model.modules():
        if not isinstance(module, (AutoWrappedModule, AutoWrappedLinear)):
            continue
        size = sum(parameter.numel() for parameter in module.parameters())
        count += size
        desired = "cuda" if count <= limit else "cpu"
        if verify_initial:
            assert str(module.onload_device) == desired, (count, module.onload_device, desired)
        module.onload_device = desired
        if desired == "cuda":
            resident += size
        wrappers += 1
    gc.collect()
    torch.cuda.empty_cache()
    return dict(wrapped_parameter_elements=count, resident_elements=resident, wrappers=wrappers)


def decode(payload):
    """功能：解码实际服务的 VIDX/JPEG 输出，验证协议帧数和尺寸。"""
    assert payload[:4] == b"VIDX"
    count = struct.unpack_from("<I", payload, 4)[0]
    position = 8
    frames = []
    for _ in range(count):
        size = struct.unpack_from("<I", payload, position)[0]
        position += 4
        with Image.open(io.BytesIO(payload[position:position + size])) as image:
            frames.append(np.asarray(image.convert("RGB")))
        position += size
    assert position == len(payload)
    return frames


def export_preview(frames, audio, output):
    """功能：导出带原始音频的对比视频；编码时间排除在推理计时外。"""
    height, width = frames[0].shape[:2]
    command = ["ffmpeg", "-nostdin", "-v", "error", "-y", "-f", "rawvideo",
               "-pix_fmt", "rgb24", "-s", f"{width}x{height}", "-r", "25", "-i", "pipe:0",
               "-i", str(audio), "-map", "0:v:0", "-map", "1:a:0", "-c:v", "libx264",
               "-preset", "fast", "-crf", "19", "-pix_fmt", "yuv420p", "-c:a", "aac",
               "-t", str(len(frames) / 25), "-movflags", "+faststart", str(output)]
    subprocess.run(command, input=np.stack(frames).tobytes(), check=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--final-only", action="store_true", help="只复测最终驻留量下的两档低分辨率")
    args = parser.parse_args()
    root, output = Path(args.root), Path(args.output)
    output.mkdir(parents=True, exist_ok=True)
    report = {"gpu": torch.cuda.get_device_name(), "torch": torch.__version__, "variants": []}
    save = lambda: (output / "matrix.json").write_text(json.dumps(report, indent=2))
    with wave.open(str(root / "input-3.36s.wav")) as wav:
        assert wav.getframerate() == 16000 and wav.getnchannels() == 1
        pcm = np.frombuffer(wav.readframes(wav.getnframes()), dtype="<i2")
    image = Image.open("/home/nm/develop/code/opentalking-main/examples/avatars/anchor/reference.png").convert("RGB")
    started = time.perf_counter()
    engine = FastEngine("/home/nm/develop/code/models/SoulX-FlashTalk-14B",
                        "/home/nm/develop/code/models/chinese-wav2vec2-base", 8_000_000_000)
    report["load_seconds"] = round(time.perf_counter() - started, 3)
    save()
    print("MODEL_READY", report["load_seconds"], flush=True)

    def measure(name, resident, steps, width, height, verify=False):
        item = dict(name=name, resident_limit=resident, steps=steps, width=width, height=height, chunks=[])
        report["variants"].append(item)
        save()
        print("VARIANT_START", name, flush=True)
        frames = []
        try:
            item["residency"] = set_residency(engine, resident, verify)
            engine.config.update(sample_steps=steps, width=width, height=height)
            torch.cuda.reset_peak_memory_stats()
            started = time.perf_counter()
            engine.initialize(image, "A person is talking. Only the foreground character is moving, the background remains static.", 42)
            torch.cuda.synchronize()
            item["init_seconds"] = round(time.perf_counter() - started, 3)
            samples = (engine.config["frame_num"] - engine.config["motion_frames_num"]) * 16000 // 25
            for index in range(3):
                torch.cuda.synchronize()
                started = time.perf_counter()
                payload = engine.render(pcm[index * samples:(index + 1) * samples].tobytes())
                torch.cuda.synchronize()
                duration = time.perf_counter() - started
                decoded = decode(payload)
                assert len(decoded) == 28 and decoded[0].shape == (height, width, 3)
                frames.extend(decoded)
                entry = dict(index=index, seconds=round(duration, 3), frames=len(decoded))
                item["chunks"].append(entry)
                save()
                print("CHUNK", name, json.dumps(entry), flush=True)
            warm = [c["seconds"] for c in item["chunks"][1:]]
            item.update(warm_mean_seconds=round(statistics.mean(warm), 3),
                        generation_fps=round(28 / statistics.mean(warm), 3),
                        realtime_ratio=round(statistics.mean(warm) / 1.12, 3),
                        peak_allocated_mib=round(torch.cuda.max_memory_allocated() / 2**20, 1),
                        peak_reserved_mib=round(torch.cuda.max_memory_reserved() / 2**20, 1))
            for index in [0, 35, 49, 77]:
                Image.fromarray(frames[index]).save(output / f"{name}-frame-{index}.png")
            export_preview(frames, root / "input-3.36s.wav", output / f"{name}.mp4")
        except torch.cuda.OutOfMemoryError as error:
            item["error"] = dict(type=type(error).__name__, message=str(error))
            print("VARIANT_OOM", name, flush=True)
        finally:
            engine.release()
            gc.collect()
            torch.cuda.empty_cache()
            save()
        print("VARIANT_DONE", json.dumps(item), flush=True)
        return item

    if args.final_only:
        # 前一轮 256x448 仍低于实时速度；只复测剩余问题，不重复完整矩阵。
        measure("final-r20-s2-256x448", 20_000_000_000, 2, 256, 448)
        measure("final-r20-s2-192x320", 20_000_000_000, 2, 192, 320)
        save()
        print("MATRIX_COMPLETE", flush=True)
        return
    baseline = measure("baseline-r8-s4-448x768", 8_000_000_000, 4, 448, 768, True)
    best = baseline
    for limit in [12_000_000_000, 16_000_000_000, 20_000_000_000]:
        item = measure(f"resident-r{limit // 10**9}-s4-448x768", limit, 4, 448, 768)
        if "error" not in item and item["warm_mean_seconds"] < best["warm_mean_seconds"]:
            best = item
        if "error" in item:
            break
    report["best_residency"] = best["resident_limit"]
    limit = best["resident_limit"]
    measure(f"steps-r{limit // 10**9}-s2-448x768", limit, 2, 448, 768)
    for width, height in [(320, 576), (256, 448)]:
        measure(f"resolution-r{limit // 10**9}-s2-{width}x{height}", limit, 2, width, height)
    save()
    print("MATRIX_COMPLETE", flush=True)


if __name__ == "__main__":
    main()

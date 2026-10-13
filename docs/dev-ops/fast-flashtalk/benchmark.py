"""Measure Fast-FlashTalk on existing weights without changing live services.

Run process: load/quantize -> cold generation -> warm generation -> save report.
Example: a 3.36-second WAV produces three 28-frame chunks at target 25 FPS.
The chunk timer measures synchronized GPU generation; it does not measure LLM,
TTS, network latency, or playback. Public generate() still returns a full video.
"""

import argparse
import json
import os
import subprocess
import time
from pathlib import Path

import numpy as np
import torch
from PIL import Image as PILImage

import fast_flashtalk.pipeline as implementation
from fast_flashtalk import Audio, FlashTalkPipeline, Image


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--checkpoint", required=True)
    parser.add_argument("--wav2vec", required=True)
    parser.add_argument("--image", required=True)
    parser.add_argument("--audio", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--resident-params", type=int, default=6_000_000_000)
    parser.add_argument("--runs", type=int, default=2)
    parser.add_argument("--no-compile", action="store_true")
    args = parser.parse_args()
    output = Path(args.output)
    output.mkdir(parents=True, exist_ok=True)
    report = {
        "torch": torch.__version__,
        "gpu": torch.cuda.get_device_name(),
        "resident_params": args.resident_params,
        "compile": not args.no_compile,
        "audio_encode_mode": "stream",
        "quant_cpu_staging": os.environ.get("FAST_FLASHTALK_QUANT_CPU_STAGING") == "1",
        "measurements": [],
    }

    def save():
        (output / "benchmark.json").write_text(json.dumps(report, indent=2))

    try:
        if args.no_compile:
            implementation.COMPILE_MODEL = False
            implementation.COMPILE_VAE = False
        start = time.perf_counter()
        pipeline = FlashTalkPipeline(
            checkpoint_dir=args.checkpoint,
            wav2vec_dir=args.wav2vec,
            num_persistent_param_in_dit=args.resident_params,
        )
        report["load_and_quantize_seconds"] = round(time.perf_counter() - start, 3)
        report["inference_config"] = pipeline.infer_params
        save()
        print("MODEL_READY", report["load_and_quantize_seconds"], flush=True)
        original_generate_chunk = pipeline.generate_chunk
        current = None
        generation_started = None

        def measured_chunk(embedding):
            torch.cuda.synchronize()
            start = time.perf_counter()
            frames = original_generate_chunk(embedding)
            torch.cuda.synchronize()
            entry = {
                "index": len(current["chunks"]),
                "seconds": round(time.perf_counter() - start, 3),
                "available_seconds": round(time.perf_counter() - generation_started, 3),
                "returned_frames": int(frames.shape[0]),
            }
            current["chunks"].append(entry)
            if entry["index"] == 0:
                frame = frames[pipeline.infer_params["motion_frames_num"]]
                pixels = frame.detach().cpu().numpy().astype(np.uint8)
                PILImage.fromarray(pixels).save(output / (current["name"] + "-first-frame.png"))
            save()
            print("CHUNK", current["name"], json.dumps(entry), flush=True)
            return frames

        pipeline.generate_chunk = measured_chunk
        for run_index in range(args.runs):
            current = {"name": "cold" if run_index == 0 else "warm", "chunks": []}
            report["measurements"].append(current)
            torch.cuda.reset_peak_memory_stats()
            generation_started = time.perf_counter()
            video = pipeline.generate(
                input_prompt="A person is talking. Only the foreground character is moving, the background remains static.",
                audio=Audio(uri=args.audio),
                image=Image(uri=args.image),
                audio_encode_mode="stream",
            )
            torch.cuda.synchronize()
            current["total_generation_seconds"] = round(time.perf_counter() - generation_started, 3)
            current["frames"] = int(len(video.data))
            current["generation_fps"] = round(current["frames"] / current["total_generation_seconds"], 3)
            current["peak_allocated_mib"] = round(torch.cuda.max_memory_allocated() / 2**20, 1)
            current["peak_reserved_mib"] = round(torch.cuda.max_memory_reserved() / 2**20, 1)
            # Preserve a preview with the same driving audio for visual inspection.
            save()
            preview = output / (current["name"] + ".mp4")
            preview_audio = Audio(uri=args.audio).load(sample_rate=16000, mono=True)
            video.merge_audio(preview_audio, output_path=str(preview))
            # osc-data may export video only. Explicitly map the original driving
            # audio so the preview can be used to inspect lip synchronization.
            muxed = output / (current["name"] + "-with-audio.mp4")
            subprocess.run(
                ["ffmpeg", "-nostdin", "-v", "error", "-y",
                 "-i", str(preview), "-i", args.audio,
                 "-map", "0:v:0", "-map", "1:a:0",
                 "-c:v", "copy", "-c:a", "aac", "-b:a", "128k",
                 "-t", str(current["frames"] / pipeline.infer_params["tgt_fps"]),
                 "-movflags", "+faststart", str(muxed)],
                check=True,
            )
            muxed.replace(preview)
            save()
            print("RUN_DONE", json.dumps(current), flush=True)
    except Exception as error:
        report["error"] = {"type": type(error).__name__, "message": str(error)}
        save()
        raise


if __name__ == "__main__":
    main()

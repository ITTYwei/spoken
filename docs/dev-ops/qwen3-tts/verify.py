"""Measure first PCM arrival, total synthesis time, and audio duration."""
import argparse
import json
import time
import wave
from pathlib import Path

import httpx


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", default="http://127.0.0.1:8091")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    results = []
    cases = [
        ("english-cold", "English", "Hello! Let's practise English together. What did you do today?", "ryan"),
        ("english-warm", "English", "Hello! Let's practise English together. What did you do today?", "ryan"),
        ("chinese", "Chinese", "你好，我们一起练习英语吧。今天你做了什么？", "vivian"),
    ]
    with httpx.Client(timeout=180) as client:
        for name, language, text, voice in cases:
            payload = dict(model="qwen3-tts", input=text, voice=voice, language=language,
                           stream=True, stream_format="audio", response_format="pcm")
            started = time.perf_counter()
            first = None
            audio = bytearray()
            with client.stream("POST", args.url + "/v1/audio/speech", json=payload) as response:
                response.raise_for_status()
                for part in response.iter_bytes():
                    if part:
                        if first is None:
                            first = time.perf_counter() - started
                        audio.extend(part)
            elapsed = time.perf_counter() - started
            if not audio or len(audio) % 2:
                raise RuntimeError("Empty or malformed PCM output: " + name)
            seconds = len(audio) / 2 / 24000
            with wave.open(str(args.output / (name + ".wav")), "wb") as wav:
                wav.setparams((1, 2, 24000, 0, "NONE", "not compressed"))
                wav.writeframes(audio)
            item = dict(case=name, first_audio_s=first, elapsed_s=elapsed,
                        audio_s=seconds, rtf=elapsed / seconds)
            results.append(item)
            print(json.dumps(item), flush=True)
    (args.output / "report.json").write_text(json.dumps(results, indent=2))


if __name__ == "__main__":
    main()

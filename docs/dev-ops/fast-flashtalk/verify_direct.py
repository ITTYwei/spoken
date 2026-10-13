"""Verify the deployed adapter with OpenTalking's real FlashTalkWSClient.

Process: init current avatar -> send two audio chunks -> inspect JPEG-decoded
frames -> close -> repeat init/close to verify lease and avatar reset.
The report measures this protocol path, excluding LLM, TTS and WebRTC playback.
"""

import asyncio
import argparse
import json
import time
import wave
from pathlib import Path

import numpy as np
from PIL import Image

from opentalking.providers.synthesis.flashtalk.ws_client import FlashTalkWSClient


async def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", default="/home/nm/develop/code/experiments/fast-flashtalk-20261012/integration-20261013")
    parser.add_argument("--chunks", type=int, default=2)
    parser.add_argument("--repeat-audio", action="store_true", help="循环已有音频，验证持续生成吞吐")
    args = parser.parse_args()
    root = Path("/home/nm/develop/code/experiments/fast-flashtalk-20261012")
    output = Path(args.output)
    output.mkdir(parents=True, exist_ok=True)
    report = {"url": "ws://127.0.0.1:18766/flashtalk", "chunks": []}
    client = FlashTalkWSClient(report["url"])
    avatar = "/home/nm/develop/code/opentalking-main/examples/avatars/anchor/reference.png"
    try:
        start = time.perf_counter()
        report["init"] = await client.init_session(avatar)
        report["init_seconds"] = round(time.perf_counter() - start, 3)
        print("INIT", json.dumps(report["init"]), flush=True)
        assert report["init"]["implementation"] == "fast-flashtalk"
        with wave.open(str(root / "input-3.36s.wav")) as audio:
            assert audio.getframerate() == 16000 and audio.getnchannels() == 1
            pcm = np.frombuffer(audio.readframes(audio.getnframes()), dtype="<i2")
        if args.repeat_audio:
            # 只用于持续吞吐测试，不把循环素材当作真实对话或口型质量评测。
            pcm = np.tile(pcm, int(np.ceil(args.chunks * client.audio_chunk_samples / len(pcm))))
        assert 1 <= args.chunks <= len(pcm) // client.audio_chunk_samples
        for index in range(args.chunks):
            start = time.perf_counter()
            frames = await client.generate(pcm[index * client.audio_chunk_samples:(index + 1) * client.audio_chunk_samples])
            assert len(frames) == client.slice_len
            assert frames[0].data.shape == (client.height, client.width, 3)
            Image.fromarray(frames[0].data[:, :, ::-1]).save(output / f"chunk-{index}-first-frame.png")
            item = {"index": index, "frames": len(frames), "seconds": round(time.perf_counter() - start, 3),
                    "height": frames[0].height, "width": frames[0].width}
            report["chunks"].append(item)
            print("CHUNK", json.dumps(item), flush=True)
        if len(report["chunks"]) > 1:
            warm = [chunk["seconds"] for chunk in report["chunks"][1:]]
            report["warm_mean_seconds"] = round(float(np.mean(warm)), 3)
            report["warm_max_seconds"] = max(warm)
            report["warm_p95_seconds"] = round(float(np.percentile(warm, 95)), 3)
        start = time.perf_counter()
        idle = await client.generate(np.zeros(client.audio_chunk_samples, np.int16))
        report["static_idle"] = {"frames": len(idle), "seconds": round(time.perf_counter() - start, 3)}
        await client.close()
        # Reinitialize another reference to prove the model lease was released
        # and the new avatar, rather than the previous motion state, owns it.
        replacement = root / "source" / "examples" / "man.png"
        if not replacement.exists():
            replacement = Path("/home/nm/develop/code/omnirt/.omnirt/model-repos/SoulX-FlashTalk/examples/man.png")
        report["second_avatar_init"] = await client.init_session(replacement)
        replacement_idle = await client.generate(np.zeros(client.audio_chunk_samples, np.int16))
        assert not np.array_equal(idle[0].data, replacement_idle[0].data)
        report["avatar_reset_verified"] = True
    finally:
        await client.close()
        (output / "direct-verification.json").write_text(json.dumps(report, indent=2))
    print("DIRECT_VERIFICATION_OK", flush=True)


if __name__ == "__main__":
    asyncio.run(main())

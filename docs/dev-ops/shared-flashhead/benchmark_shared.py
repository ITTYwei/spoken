"""Protocol-level isolation and throughput test against the isolated GPU bridge."""
import asyncio
import base64
import hashlib
import json
import statistics
import struct
import time
import wave
from pathlib import Path

import numpy as np
from PIL import Image
import websockets

ROOT = Path(__file__).resolve().parent
URL = "ws://127.0.0.1:18767/v1/avatar/realtime"
SOURCE = Path("/home/nm/develop/code/backup/SoulX-FlashHead/examples")


async def create(image):
    ws = await websockets.connect(URL, max_size=50 * 1024 * 1024, open_timeout=10)
    try:
        await ws.send(json.dumps({"type": "session.create", "inputs": {
            "image_b64": base64.b64encode(Path(image).read_bytes()).decode()}}))
        info = json.loads(await asyncio.wait_for(ws.recv(), 180))
        assert info["type"] == "session.created", info
        return ws, info
    except BaseException:
        await ws.close()
        raise


async def close(ws):
    await ws.send(json.dumps({"type": "session.close"}))
    assert json.loads(await ws.recv())["type"] == "session.closed"
    await ws.close()
    await asyncio.sleep(0.05)


async def generate(ws, pcm, info):
    t = time.monotonic()
    await ws.send(b"AUDI" + pcm)
    result = await asyncio.wait_for(ws.recv(), 180)
    assert isinstance(result, bytes) and result[:4] == b"VIDX", result
    count = struct.unpack_from("<I", result, 4)[0]
    assert count == info["video"]["frame_count"]
    # Verify actual JPEG shape, not only the session metadata.
    import io
    size = struct.unpack_from("<I", result, 8)[0]
    with Image.open(io.BytesIO(result[12:12 + size])) as im:
        assert im.size == (info["video"]["width"], info["video"]["height"])
    return hashlib.sha256(result).hexdigest(), time.monotonic() - t, result


async def main():
    # A different aspect ratio exercises session-owned dimensions and compiler shapes.
    with Image.open(SOURCE / "girl.png") as im:
        im.crop((64, 0, 448, 512)).save(ROOT / "portrait-test.png")
    with wave.open(str(SOURCE / "podcast_sichuan_16k.wav")) as wav:
        assert wav.getframerate() == 16000 and wav.getnchannels() == 1
        raw = wav.readframes(15360 * 30)
    chunks = [raw[i:i+30720] for i in range(0, len(raw)-30719, 30720)]
    assert len(chunks) >= 12
    report = {}
    # Compiler warmup is reported separately from steady-state isolation.
    for ref in (SOURCE / "girl.png", ROOT / "portrait-test.png"):
        warm, wi = await create(ref)
        await generate(warm, chunks[0], wi)
        await close(warm)
    a, info = await create(SOURCE / "girl.png")
    baseline = [await generate(a, pcm, info) for pcm in chunks[:3]]
    report["single_seconds"] = [item[1] for item in baseline]
    await close(a)
    a, ai = await create(SOURCE / "girl.png")
    first = await generate(a, chunks[0], ai)
    b, bi = await create(ROOT / "portrait-test.png")
    c = await websockets.connect(URL)
    await c.send(json.dumps({"type": "session.create", "inputs": {
        "image_b64": base64.b64encode((SOURCE / "girl.png").read_bytes()).decode()}}))
    assert json.loads(await c.recv())["type"] == "error"
    await c.close()
    interleaved = [first]
    for pcm in chunks[1:3]:
        results = await asyncio.gather(generate(a, pcm, ai), generate(b, pcm, bi))
        interleaved.append(results[0])
    report["isolation_hashes_equal"] = ([x[0] for x in baseline] ==
                                        [x[0] for x in interleaved])
    report["baseline_hashes"] = [x[0] for x in baseline]
    report["interleaved_hashes"] = [x[0] for x in interleaved]
    def decode(payload):
        import cv2
        frames, offset = [], 8
        while offset < len(payload):
            size = struct.unpack_from("<I", payload, offset)[0]
            offset += 4
            frames.append(cv2.imdecode(np.frombuffer(payload[offset:offset+size], dtype=np.uint8), 1))
            offset += size
        return np.stack(frames)
    report["pixel_mae"] = [float(np.abs(decode(x[2]).astype(float)-decode(y[2])).mean())
                           for x, y in zip(baseline, interleaved)]
    print(json.dumps(report, indent=2), flush=True)
    assert report["isolation_hashes_equal"], "A changed after B initialized or generated"
    report["shapes"] = [ai["video"], bi["video"]]
    durations = [[], []]
    start = time.monotonic()
    for pcm in chunks[3:12]:
        results = await asyncio.gather(generate(a, pcm, ai), generate(b, pcm, bi))
        for i, value in enumerate(results):
            durations[i].append(value[1])
    report["dual_wall_seconds"] = time.monotonic() - start
    report["audio_seconds_per_user"] = 9 * 0.96
    report["dual_seconds"] = durations
    report["dual_mean_seconds"] = [statistics.mean(x) for x in durations]
    # Abrupt disconnect A, then prove B can keep generating and a new client can join.
    await a.close()
    await asyncio.sleep(0.1)
    await generate(b, chunks[0], bi)
    d, di = await create(SOURCE / "girl.png")
    await asyncio.gather(generate(b, chunks[1], bi), generate(d, chunks[1], di))
    await close(d)
    await close(b)
    report["capacity_and_disconnect_recovery"] = True
    (ROOT / "benchmark-result.json").write_text(json.dumps(report, indent=2))
    print(json.dumps(report, indent=2), flush=True)


if __name__ == "__main__":
    asyncio.run(main())

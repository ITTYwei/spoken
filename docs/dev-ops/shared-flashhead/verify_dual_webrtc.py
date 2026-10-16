"""Two real WebRTC peers: simultaneous speech, isolated interrupt and queued delete."""
import argparse
import asyncio
import contextlib
import json
import time
import uuid
from pathlib import Path

import aiohttp
import numpy as np
from aiortc import RTCConfiguration, RTCPeerConnection, RTCSessionDescription
from aiortc.mediastreams import MediaStreamError


async def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=18001)
    parser.add_argument("--output", default="dual-webrtc-result.json")
    args = parser.parse_args()
    base = f"http://127.0.0.1:{args.port}"
    clients, created_ids = [], []
    report = {"port": args.port, "clients": [], "started": time.time()}
    async with aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=240)) as http:
        async def request(method, path, data=None):
            async with http.request(method, base + path, json=data) as response:
                text = await response.text()
                if response.status >= 400:
                    raise RuntimeError(f"{method} {path}: {response.status}: {text[:500]}")
                return json.loads(text) if text else {}

        async def create():
            result = await request("POST", "/sessions", {
                "avatar_id": "anchor", "model": "flashhead", "tts_provider": "openai_compatible",
                "tts_voice": "Ethan", "agent_enabled": False,
                "knowledge_enabled": False, "memory_enabled": False})
            created_ids.append(result["session_id"])
            return result

        async def attach():
            result = await create()
            sid = result["session_id"]
            async with asyncio.timeout(180):
                while True:
                    state = await request("GET", f"/sessions/{sid}")
                    if state.get("state") in {"worker_ready", "ready"}:
                        break
                    if state.get("state") in {"closed", "error"}:
                        raise RuntimeError(str(state))
                    await asyncio.sleep(0.1)
            pc = RTCPeerConnection(RTCConfiguration(iceServers=[]))
            client = {"sid": sid, "pc": pc, "tasks": [], "events": [], "video": 0,
                      "audio": 0, "audio_peak": 0, "ended": {}, "started": {}}
            clients.append(client)

            async def receive(track):
                try:
                    while True:
                        frame = await track.recv()
                        client[track.kind] += 1
                        if track.kind == "audio":
                            peak = int(np.abs(frame.to_ndarray().astype(np.int32)).max())
                            client["audio_peak"] = max(peak, client["audio_peak"])
                        else:
                            client["shape"] = [frame.width, frame.height]
                except MediaStreamError:
                    pass

            @pc.on("track")
            def track_received(track):
                client["tasks"].append(asyncio.create_task(receive(track)))

            response = await http.get(base + f"/sessions/{sid}/events")
            response.raise_for_status()
            client["sse"] = response

            async def events():
                kind = ""
                async for raw in response.content:
                    line = raw.decode().strip()
                    if line.startswith("event:"):
                        kind = line[6:].strip()
                    elif line.startswith("data:"):
                        data = json.loads(line[5:].strip())
                        client["events"].append({"event": kind, "data": data})
                        utterance = data.get("utterance_id")
                        if kind == "speech.ended":
                            client["ended"].setdefault(utterance, asyncio.Event()).set()
                        if kind == "speech.media_started":
                            client["started"].setdefault(utterance, asyncio.Event()).set()
            client["tasks"].append(asyncio.create_task(events()))
            for kind in ("video", "audio"):
                pc.addTransceiver(kind, direction="recvonly")
            await pc.setLocalDescription(await pc.createOffer())
            answer = await request("POST", f"/sessions/{sid}/webrtc/offer", {
                "type": pc.localDescription.type, "sdp": pc.localDescription.sdp})
            await pc.setRemoteDescription(RTCSessionDescription(sdp=answer["sdp"], type=answer["type"]))
            async with asyncio.timeout(30):
                while pc.connectionState != "connected":
                    assert pc.connectionState != "failed"
                    await asyncio.sleep(0.05)
            return client

        async def say(client, text):
            command = "shared-test-" + uuid.uuid4().hex
            client["ended"][command] = asyncio.Event()
            client["started"][command] = asyncio.Event()
            await request("POST", f"/sessions/{client['sid']}/say", {
                "text": text, "command_id": command,
                "tts_provider": "openai_compatible", "voice": "Ethan"})
            return command

        try:
            a, b = await asyncio.gather(attach(), attach())
            c = await create()
            assert c["status"] == "queued", c
            await request("DELETE", f"/sessions/{c['session_id']}")
            created_ids.remove(c["session_id"])
            text = "Hello! Let's practice speaking English together. Tell me about your plans for the weekend. What would you like to do with your friends?"
            ca, cb = await asyncio.gather(say(a, text), say(b, text))
            await asyncio.wait_for(asyncio.gather(a["started"][ca].wait(), b["started"][cb].wait()), 120)
            started = time.monotonic()
            await request("POST", f"/sessions/{a['sid']}/interrupt")
            report["interrupt_http_seconds"] = time.monotonic() - started
            await asyncio.wait_for(a["ended"][ca].wait(), 60)
            report["interrupt_end_seconds"] = time.monotonic() - started
            await asyncio.wait_for(b["ended"][cb].wait(), 120)
            assert any(e["event"] == "speech.ended" and
                       e["data"].get("utterance_id") == cb and e["data"].get("text") == text
                       for e in b["events"]), "B did not complete its own text"
            assert not any(e["event"] == "speech.ended" and
                           e["data"].get("utterance_id") == cb and
                           e["data"].get("reason") in {"cancelled", "interrupted", "error"}
                           for e in b["events"])
            ca2 = await say(a, "Hello again! My conversation still works.")
            await asyncio.wait_for(a["ended"][ca2].wait(), 120)
            assert a["started"][ca2].is_set()
            cb2 = await say(b, "I can keep speaking while the other user leaves. How was your day?")
            await asyncio.wait_for(b["started"][cb2].wait(), 120)
            await request("DELETE", f"/sessions/{a['sid']}")
            created_ids.remove(a["sid"])
            await a["pc"].close()
            await asyncio.wait_for(b["ended"][cb2].wait(), 120)
            assert any(e["event"] == "speech.ended" and
                       e["data"].get("utterance_id") == cb2 and e["data"].get("text")
                       for e in b["events"]), "B did not finish after A disconnected"
            await asyncio.sleep(1)
            for client in (a, b):
                assert client["video"] > 0 and client["audio"] > 0 and client["audio_peak"] > 0
                report["clients"].append({key: client[key] for key in
                    ("sid", "video", "audio", "audio_peak", "shape", "events")})
            report["passed"] = True
        except BaseException as exc:
            report["error"] = repr(exc)
            raise
        finally:
            for sid in created_ids:
                with contextlib.suppress(Exception):
                    await request("DELETE", f"/sessions/{sid}")
            for client in clients:
                await client["pc"].close()
                client["sse"].close()
                for task in client["tasks"]:
                    task.cancel()
                await asyncio.gather(*client["tasks"], return_exceptions=True)
            Path(args.output).write_text(json.dumps(report, indent=2))
            print(json.dumps({key: value for key, value in report.items() if key != "clients"}), flush=True)
            print([{key: client[key] for key in ("video", "audio", "audio_peak", "shape")}
                   for client in clients if "shape" in client], flush=True)


if __name__ == "__main__":
    asyncio.run(main())

"""Verify local Qwen3-TTS -> FlashHead -> actual WebRTC tracks.

Process: select server default model -> attach receive-only peer ->
say -> interrupt during GPU work -> say again -> close only the test session.
The report separates server events from actual received media; no LLM is called.
"""

import asyncio
import argparse
import json
import time
import uuid
from pathlib import Path

import aiohttp
import numpy as np
from aiortc import RTCConfiguration, RTCPeerConnection, RTCSessionDescription
from aiortc.mediastreams import MediaStreamError


async def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", default="./qwen3-tts-webrtc-results")
    args = parser.parse_args()
    output = Path(args.output)
    output.mkdir(parents=True, exist_ok=True)
    report = {"events": [], "commands": [], "received_video_frames": 0,
              "received_audio_frames": 0, "audio_peak": 0, "peer_states": []}
    started = time.perf_counter()
    pc = RTCPeerConnection(RTCConfiguration(iceServers=[]))
    readers = []
    ended = {}
    media_seen = None
    speech_frame_saved = False
    session_id = None
    event_task = None
    event_response = None

    async def receive(track):
        nonlocal speech_frame_saved
        try:
            while True:
                frame = await track.recv()
                if track.kind == "video":
                    report["received_video_frames"] += 1
                    report["video_shape"] = [frame.width, frame.height]
                    if media_seen is not None and report["received_video_frames"] >= media_seen + 6 and not speech_frame_saved:
                        frame.to_image().save(output / "webrtc-speech-frame.png")
                        speech_frame_saved = True
                else:
                    report["received_audio_frames"] += 1
                    peak = int(np.max(np.abs(frame.to_ndarray().astype(np.int32))))
                    report["audio_peak"] = max(report["audio_peak"], peak)
        except MediaStreamError:
            pass

    @pc.on("track")
    def on_track(track):
        readers.append(asyncio.create_task(receive(track)))

    @pc.on("connectionstatechange")
    async def on_state():
        report["peer_states"].append(pc.connectionState)
        print("PEER", pc.connectionState, flush=True)

    async with aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=600)) as http:
        async def request(method, path, body=None):
            async with http.request(method, "http://127.0.0.1:8001" + path, json=body) as response:
                data = await response.json()
                if response.status >= 400:
                    raise RuntimeError(f"{method} {path}: HTTP {response.status}: {data}")
                return data

        async def events():
            nonlocal media_seen
            kind = None
            async for raw in event_response.content:
                line = raw.decode().strip()
                if line.startswith("event:"):
                    kind = line[6:].strip()
                elif line.startswith("data:"):
                    data = json.loads(line[5:].strip())
                    item = {"event": kind, "seconds": round(time.perf_counter() - started, 3), "data": data}
                    report["events"].append(item)
                    print("EVENT", json.dumps(item), flush=True)
                    if kind == "speech.media_started":
                        media_seen = report["received_video_frames"]
                    if kind == "speech.ended":
                        ended.setdefault(data.get("utterance_id"), asyncio.Event()).set()

        async def say(text):
            command = {"text": text, "command_id": "fast-integration-" + uuid.uuid4().hex[:12],
                       "voice": "Cherry", "tts_provider": "openai_compatible"}
            ended[command["command_id"]] = asyncio.Event()
            report["commands"].append({**command, "seconds": round(time.perf_counter() - started, 3)})
            await request("POST", f"/sessions/{session_id}/say", command)
            return command["command_id"]

        try:
            models = await request("GET", "/models")
            report["default_model"] = models["default_model"]
            assert report["default_model"] == "flashhead"
            # This API requires an explicit model; mimic a client selecting the
            # advertised default rather than assuming /sessions supplies it.
            created = await request("POST", "/sessions", {
                "model": report["default_model"],
                "avatar_id": "anchor", "tts_provider": "openai_compatible", "tts_voice": "Cherry",
                "agent_enabled": False, "knowledge_enabled": False, "memory_enabled": False,
            })
            session_id = created["session_id"]
            report["session_id"] = session_id
            print("SESSION", session_id, flush=True)
            async with asyncio.timeout(150):
                while True:
                    state = await request("GET", f"/sessions/{session_id}")
                    if state.get("state") in {"worker_ready", "ready"}:
                        report["session_model"] = state.get("model")
                        report["session_ready_seconds"] = round(time.perf_counter() - started, 3)
                        break
                    if state.get("state") in {"closed", "error", "failed"}:
                        raise RuntimeError(f"Session failed: {state}")
                    await asyncio.sleep(0.5)
            assert report["session_model"] == "flashhead", state
            event_response = await http.get(f"http://127.0.0.1:8001/sessions/{session_id}/events")
            event_response.raise_for_status()
            event_task = asyncio.create_task(events())
            pc.addTransceiver("video", direction="recvonly")
            pc.addTransceiver("audio", direction="recvonly")
            await pc.setLocalDescription(await pc.createOffer())
            answer = await request("POST", f"/sessions/{session_id}/webrtc/offer", {
                "type": pc.localDescription.type, "sdp": pc.localDescription.sdp,
            })
            await pc.setRemoteDescription(RTCSessionDescription(sdp=answer["sdp"], type=answer["type"]))
            async with asyncio.timeout(20):
                while pc.connectionState != "connected":
                    if pc.connectionState == "failed":
                        raise RuntimeError("WebRTC connection failed")
                    await asyncio.sleep(0.1)
            first = await say("Hello! How are you today?")
            await asyncio.wait_for(ended[first].wait(), 180)
            assert any(e["event"] == "speech.media_started" and e["data"].get("utterance_id") == first
                       for e in report["events"]), "First utterance ended without media; inspect TTS errors"
            # Interrupt while the actual backend is processing a non-silent chunk.

            second = await say("Let's talk about your plans for the weekend. What would you like to do?")
            async with asyncio.timeout(30):
                while not any(e['event'] == 'speech.media_started' and e['data'].get('utterance_id') == second for e in report['events']):
                    await asyncio.sleep(0.1)
            interrupt_started = time.perf_counter()
            report["interrupt_response"] = await request("POST", f"/sessions/{session_id}/interrupt")
            report["interrupt_http_seconds"] = round(time.perf_counter() - interrupt_started, 3)
            await asyncio.wait_for(ended[second].wait(), 60)
            report["interrupt_reset_seconds"] = round(time.perf_counter() - interrupt_started, 3)
            third = await say("Hello again!")
            await asyncio.wait_for(ended[third].wait(), 180)
            assert any(e["event"] == "speech.media_started" and e["data"].get("utterance_id") == third
                       for e in report["events"]), "Post-interrupt utterance ended without media"
            await asyncio.sleep(2)
            assert report["received_video_frames"] > 0
            assert report["received_audio_frames"] > 0 and report["audio_peak"] > 0
            report["post_interrupt_speech_verified"] = True
            print("OPENTALKING_WEBRTC_VERIFICATION_OK", flush=True)
        except Exception as error:
            report["error"] = {"type": type(error).__name__, "message": str(error)}
            raise
        finally:
            await pc.close()
            if event_task:
                event_task.cancel()
            if event_response:
                event_response.close()
            for task in readers:
                task.cancel()
            await asyncio.gather(*(readers + ([event_task] if event_task else [])), return_exceptions=True)
            if session_id:
                report["cleanup"] = await request("DELETE", f"/sessions/{session_id}")
            report["elapsed_seconds"] = round(time.perf_counter() - started, 3)
            (output / "opentalking-verification.json").write_text(json.dumps(report, indent=2))


if __name__ == "__main__":
    asyncio.run(main())

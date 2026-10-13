"""真实 FlashHead + TTS + WebRTC：延迟 end，验证首句在完整文本之前发声，并验证打断后再播。"""
import asyncio
import json
import time
import uuid
from pathlib import Path
import aiohttp
import numpy as np
from aiortc import RTCPeerConnection, RTCConfiguration, RTCSessionDescription


async def main():
    report = {'events': [], 'rounds': [], 'video_frames': 0, 'audio_frames': 0,
              'pts_regressions': {'video': 0, 'audio': 0}, 'max_arrival_gap_s': {'video': 0., 'audio': 0.}}
    pc = RTCPeerConnection(RTCConfiguration(iceServers=[]))
    tasks = []
    start = time.monotonic()
    sid = None
    media = asyncio.Event()
    audio = asyncio.Event()
    previous_pts = {}  # 两条真实接收轨的时间戳，验证句间连续性。
    previous_arrival = {}  # 接收间隔；只统计首轮语音窗口。
    collecting = False

    async def recv(track):
        while True:
            frame = await track.recv()
            report[track.kind + '_frames'] += 1
            if collecting:
                now = time.monotonic()
                if track.kind in previous_pts and frame.pts <= previous_pts[track.kind]:
                    report['pts_regressions'][track.kind] += 1
                if track.kind in previous_arrival:
                    report['max_arrival_gap_s'][track.kind] = max(
                        report['max_arrival_gap_s'][track.kind], now - previous_arrival[track.kind])
                previous_pts[track.kind] = frame.pts
                previous_arrival[track.kind] = now
            if track.kind == 'audio' and np.max(np.abs(frame.to_ndarray().astype(np.int32))) > 200:
                if not audio.is_set(): report['first_audible_s'] = round(time.monotonic() - start, 3)
                audio.set()

    @pc.on('track')
    def track_received(track):
        tasks.append(asyncio.create_task(recv(track)))

    async with aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=180)) as http:
        async def req(method, path, data=None):
            async with http.request(method, 'http://127.0.0.1:8001' + path, json=data) as response:
                response.raise_for_status()
                return await response.json()

        async def events():
            async with http.get(f'http://127.0.0.1:8001/sessions/{sid}/events') as response:
                kind = ''
                async for raw in response.content:
                    line = raw.decode().strip()
                    if line.startswith('event:'): kind = line[6:].strip()
                    elif line.startswith('data:'):
                        data = json.loads(line[5:])
                        report['events'].append(dict(event=kind, data=data, seconds=round(time.monotonic()-start,3)))
                        if kind == 'speech.media_started': media.set()

        async def connect():
            ws = await http.ws_connect(f'http://127.0.0.1:8001/sessions/{sid}/text_stream', max_msg_size=16384)
            rid = 'probe-' + uuid.uuid4().hex
            await ws.send_json(dict(type='begin', replyId=rid, seq=0, voice='Cherry', ttsProvider='dashscope'))
            ack = await ws.receive_json(timeout=30)
            assert ack.get('type') == 'ack', ack
            report['rounds'].append(rid)
            return ws, rid

        async def send(ws, rid, seq, kind, text=None):
            message = dict(type=kind, replyId=rid, seq=seq)
            if text is not None: message['text'] = text
            await ws.send_json(message)
            ack = await ws.receive_json(timeout=25)
            assert ack.get('type') == 'ack' and ack.get('seq') == seq, ack
            return ack

        try:
            models = await req('GET', '/models')
            assert models['default_model'] == 'flashhead', models['default_model']
            sid = (await req('POST', '/sessions', dict(model='flashhead', avatar_id='anchor',
                    tts_provider='dashscope', tts_voice='Cherry')))['session_id']
            report['session_id'] = sid
            async with asyncio.timeout(120):
                while (await req('GET', f'/sessions/{sid}')).get('state') not in ('ready', 'worker_ready'):
                    await asyncio.sleep(.25)
            tasks.append(asyncio.create_task(events()))
            pc.addTransceiver('video', direction='recvonly')
            pc.addTransceiver('audio', direction='recvonly')
            await pc.setLocalDescription(await pc.createOffer())
            answer = await req('POST', f'/sessions/{sid}/webrtc/offer', dict(sdp=pc.localDescription.sdp, type='offer'))
            await pc.setRemoteDescription(RTCSessionDescription(**answer))
            async with asyncio.timeout(20):
                while pc.connectionState != 'connected': await asyncio.sleep(.1)
            ws, rid = await connect()
            report['begin_s'] = round(time.monotonic()-start, 3)
            await send(ws, rid, 1, 'append', 'Hello! It is good to see you today. ')
            dup = await send(ws, rid, 1, 'append', 'Hello! It is good to see you today. ')
            assert dup['status'] == 'duplicate'
            # 尚未发送后半段与 end；必须收到真实非静音音频，否则不算流式成功。
            await asyncio.wait_for(audio.wait(), 40)
            collecting = True
            report['played_before_end'] = True
            await asyncio.sleep(1.2)  # 模拟文本网络抖动。
            await send(ws, rid, 2, 'append', 'What would you like to talk about?')
            report['text_end_s'] = round(time.monotonic()-start, 3)
            await send(ws, rid, 3, 'end')
            done = await ws.receive_json(timeout=90)
            assert done.get('type') == 'playback_end', done
            report['playback_end_s'] = round(time.monotonic()-start, 3)
            collecting = False
            assert report['pts_regressions'] == {'video': 0, 'audio': 0}, report['pts_regressions']
            await ws.close()
            ws, rid = await connect()
            await send(ws, rid, 1, 'append', 'Let us discuss a very long story about a journey around the world. ')
            await asyncio.sleep(.3)
            # 复现手机发现的竞态：HTTP 打断完成确认后立刻开始下一轮。
            report['interrupt_http_start_s'] = round(time.monotonic()-start, 3)
            await req('POST', f'/sessions/{sid}/interrupt')
            report['interrupt_http_end_s'] = round(time.monotonic()-start, 3)
            await ws.close()
            ws, rid = await connect()
            await send(ws, rid, 1, 'append', 'Hello again!')
            await send(ws, rid, 2, 'end')
            done = await ws.receive_json(timeout=90)
            assert done.get('type') == 'playback_end', done
            report['post_interrupt_completed'] = True
            await ws.close()
            print('STREAM_WEBRTC_PASS', json.dumps({k:v for k,v in report.items() if k != 'events'}), flush=True)
        except Exception as exc:
            report['error'] = repr(exc)
            raise
        finally:
            await pc.close()
            for task in tasks: task.cancel()
            await asyncio.gather(*tasks, return_exceptions=True)
            if sid: await req('DELETE', f'/sessions/{sid}')
            Path('/home/nm/develop/code/backup/flashhead-streaming-20261013/stream-report.json').write_text(json.dumps(report, indent=2))


if __name__ == '__main__':
    asyncio.run(main())

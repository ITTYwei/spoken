"""真实 Java 教学编排 + Agent + FlashHead + WebRTC；需临时SSH转发Java至18092。"""
import asyncio
import json
import uuid
from pathlib import Path
import aiohttp
from aiortc import RTCPeerConnection, RTCConfiguration, RTCSessionDescription


async def main():
    api = 'http://127.0.0.1:18092/api/v1/sessions'
    device = 'teaching-probe-' + uuid.uuid4().hex[:16]
    report = {'device': device, 'events': [], 'rounds': [], 'audio_frames': 0, 'video_frames': 0}
    pc = RTCPeerConnection(RTCConfiguration(iceServers=[]))
    tasks = []
    sid = None
    queue = asyncio.Queue()
    async def receive(track):
        while True:
            await track.recv()
            report[track.kind + '_frames'] += 1
    @pc.on('track')
    def track_received(track):
        tasks.append(asyncio.create_task(receive(track)))
    async with aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=180)) as http:
        async def req(method, path='', data=None):
            async with http.request(method, api + path, json=data) as response:
                body = await response.json()
                if response.status >= 400: raise RuntimeError((response.status, body))
                return body
        async def events():
            async with http.get(api + '/' + sid + '/events') as response:
                async for raw in response.content:
                    line = raw.decode().strip()
                    if line.startswith('data:'):
                        event = json.loads(line[5:].strip())
                        report['events'].append(event)
                        await queue.put(event)
        async def wait_done():
            async with asyncio.timeout(45):
                while True:
                    event = await queue.get()
                    if event['type'] == 'error': raise RuntimeError(event)
                    if event['type'] == 'assistant.done': return event['data']
        try:
            created = await req('POST', data={'deviceId': device, 'model': 'flashhead', 'scenarioId': 'free'})
            sid = created['sessionId']
            report['session_id'] = sid
            async with asyncio.timeout(60):
                while (await req('GET', '/' + sid))['status'] == 'QUEUED': await asyncio.sleep(.25)
            tasks.append(asyncio.create_task(events()))
            pc.addTransceiver('video', direction='recvonly')
            pc.addTransceiver('audio', direction='recvonly')
            await pc.setLocalDescription(await pc.createOffer())
            answer = await req('POST', '/' + sid + '/offer', {'sdp': pc.localDescription.sdp, 'type': 'offer'})
            await pc.setRemoteDescription(RTCSessionDescription(**answer))
            async with asyncio.timeout(20):
                while pc.connectionState != 'connected': await asyncio.sleep(.1)
            report['opening'] = await req('POST', '/' + sid + '/opening')
            await wait_done()
            for text, action in [('I like coffee', 'QUESTION'), ('我不会说', 'READ'),
                                 ('不想读', 'QUESTION'), ('听不懂', 'QUESTION')]:
                await req('POST', '/' + sid + '/speak', {'text': text})
                spoken = await wait_done()
                report['rounds'].append({'input': text, 'expected_action': action, 'spoken': spoken})
                if action == 'READ':
                    assert '?' not in spoken and '？' not in spoken, spoken
                    assert '听我说' in spoken and '读这一句' in spoken, spoken
                else:
                    assert spoken.count('?') + spoken.count('？') == 1, spoken
                    assert not any(x in spoken for x in ('跟我读', '试着读', 'repeat after')), spoken
                if text == '不想读': assert '也没关系' in spoken, spoken
                if text == '听不懂': assert '没关系' in spoken, spoken
            report['turns'] = await req('GET', '/' + sid + '/turns')
            assert report['audio_frames'] > 0 and report['video_frames'] > 0
            report['pass'] = True
        finally:
            if sid:
                try: await req('DELETE', '/' + sid)
                except Exception: pass
            await pc.close()
            for task in tasks: task.cancel()
            await asyncio.gather(*tasks, return_exceptions=True)
            Path('/home/nm/develop/code/backup/flashhead-streaming-20261013/teaching-report.json').write_text(
                json.dumps(report, ensure_ascii=False, indent=2))
    print(json.dumps(report, ensure_ascii=False))


asyncio.run(main())

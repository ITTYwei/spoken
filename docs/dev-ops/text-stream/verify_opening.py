"""真实首次/无发言再次进入/可靠续聊；在服务器运行，需临时Java反向转发18092。"""
import asyncio
import json
import uuid
import os
from pathlib import Path
import aiohttp
from aiortc import RTCPeerConnection, RTCConfiguration, RTCSessionDescription

async def main():
    api='http://127.0.0.1:18092/api/v1/sessions'
    device='opening-v2-probe-'+uuid.uuid4().hex[:16]
    model=os.environ.get('OPENING_TEST_MODEL','flashhead')
    report={'device':device,'model':model,'sessions':[],'audio_frames':0,'video_frames':0}
    async with aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=180)) as http:
        async def req(method,path='',data=None):
            async with http.request(method,api+path,json=data) as response:
                body=await response.json()
                if response.status>=400: raise RuntimeError((response.status,body))
                return body
        async def run(kind):
            sid=None; pc=RTCPeerConnection(RTCConfiguration(iceServers=[])); tasks=[]; queue=asyncio.Queue()
            entry={'kind':kind}; report['sessions'].append(entry)
            async def receive(track):
                while True:
                    await track.recv(); report[track.kind+'_frames']+=1
            @pc.on('track')
            def track(track): tasks.append(asyncio.create_task(receive(track)))
            async def events():
                async with http.get(api+'/'+sid+'/events') as response:
                    async for raw in response.content:
                        line=raw.decode().strip()
                        if line.startswith('data:'): await queue.put(json.loads(line[5:].strip()))
            async def done():
                async with asyncio.timeout(65):
                    while True:
                        event=await queue.get()
                        if event['type']=='error': raise RuntimeError(event)
                        if event['type']=='assistant.done': return event['data']
            try:
                created=await req('POST',data={'deviceId':device,'model':model,'scenarioId':'free'})
                sid=created['sessionId']; entry['session_id']=sid
                async with asyncio.timeout(60):
                    while (await req('GET','/'+sid))['status']=='QUEUED': await asyncio.sleep(.25)
                tasks.append(asyncio.create_task(events()))
                pc.addTransceiver('video',direction='recvonly'); pc.addTransceiver('audio',direction='recvonly')
                await pc.setLocalDescription(await pc.createOffer())
                answer=await req('POST','/'+sid+'/offer',{'sdp':pc.localDescription.sdp,'type':'offer'})
                await pc.setRemoteDescription(RTCSessionDescription(**answer))
                async with asyncio.timeout(20):
                    while pc.connectionState!='connected': await asyncio.sleep(.1)
                opening=await req('POST','/'+sid+'/opening'); entry['opening']=opening
                assert opening['spoken'],opening
                repeated=await req('POST','/'+sid+'/opening'); entry['repeated']=repeated
                assert not repeated['spoken'] and repeated['reason']=='already_spoken',repeated
                spoken=await done(); entry['played']=spoken
                assert 'Amy' not in spoken and 'Alex' not in spoken and 'anchor' not in spoken,spoken
                if kind=='first':
                    assert spoken.startswith('你好，我是你的英语口语搭子。'),spoken
                    assert spoken.endswith('先打个招呼吧，中文或英文都可以。'),spoken
                    assert '?' not in spoken and '？' not in spoken,spoken
                    # 保持零用户发言，验证介绍记录独立于聊天历史。
                else:
                    assert '没听懂、不会表达' not in spoken,spoken
                    assert spoken.count('?')+spoken.count('？')==1,spoken
                    if kind=='resume': assert '旅行' in spoken and '换个话题' in spoken,spoken
                    if kind=='return_without_speech':
                        assert '旅行' not in spoken,spoken
                        await req('POST','/'+sid+'/speak',{'text':'下次聊旅行'})
                        entry['deferred_reply']=await done()
                entry['pass']=True
            finally:
                if sid:
                    try: await req('DELETE','/'+sid)
                    except Exception as error: entry['close_error']=type(error).__name__
                await pc.close()
                for task in tasks: task.cancel()
                await asyncio.gather(*tasks,return_exceptions=True)
        try:
            for kind in ('first','return_without_speech','resume'): await run(kind)
            assert report['audio_frames']>0 and report['video_frames']>0
            report['pass']=True
        finally:
            Path('/home/nm/develop/code/backup/flashhead-streaming-20261013/opening-v2-'+model+'-report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2))
    print(json.dumps(report,ensure_ascii=False))

asyncio.run(main())

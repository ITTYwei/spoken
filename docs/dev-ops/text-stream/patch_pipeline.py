"""在已备份的 OpenTalking 工作树应用外部文本入口；断言锚点，拒绝错版本覆盖。"""
from pathlib import Path
import sys

root = Path(sys.argv[1])
# JPEG 整批解码是 CPU 操作。即使线程池 map 等待也不能放在 RTC 事件循环中。
client = root / 'opentalking/providers/synthesis/flashhead/ws_client.py'
source = client.read_text()
if 'await asyncio.to_thread(_decode_batch)' not in source:
    source = source.replace('import base64\n', 'import asyncio\nimport base64\n', 1)
    start = source.index('        if JPEG_DECODE_WORKERS <= 1 or len(jpeg_parts) <= 1:')
    end = source.index('        t_decode = time.monotonic()', start)
    block = source[start:end]
    source = source[:start] + '        def _decode_batch():\n' + ''.join(
        '    ' + line if line.strip() else line for line in block.splitlines(keepends=True)
    ) + '            return bgr_frames\n\n        bgr_frames = await asyncio.to_thread(_decode_batch)\n' + source[end:]
    client.write_text(source)

p = root / 'opentalking/pipeline/speak/synthesis_runner.py'
s = p.read_text()
if 'external_text=None' not in s:
    marker = '    async def speak(\n'
    start = s.index(marker)
    head, body = s[:start], s[start:]
    body = body.replace('        direct: bool = False,\n', '        direct: bool = False,\n        external_text=None,\n', 1)
    body = body.replace('            prebuffer_chunks = self._prebuffer_chunks(speech_text=text)',
                        '            prebuffer_chunks = 1 if external_text is not None else self._prebuffer_chunks(speech_text=text)', 1)
    old = '''                        full_response = text
                        for sentence in splitter.feed(text):
                            if self._interrupt.is_set():
                                break
                            await _queue_sentence_for_tts(sentence)'''
    new = '''                        async def pieces():
                            if external_text is None:
                                yield text
                            else:
                                async for delta in external_text.pieces():
                                    yield delta
                        full_response = ""
                        async for delta in pieces():
                            if self._interrupt.is_set():
                                break
                            full_response += delta
                            for sentence in splitter.feed(delta):
                                await _queue_sentence_for_tts(sentence)'''
    assert old in body
    body = body.replace(old, new, 1)
    # TaskGroup 保证任意子任务失败/取消时另一条流水线一同停止，防止旧 TTS 继续入队。
    old = '''                    await asyncio.gather(
                        _direct_feeder() if direct else _llm_feeder(),
                        _tts_worker(),
                    )'''
    new = '''                    async with asyncio.TaskGroup() as group:
                        group.create_task(_direct_feeder() if direct else _llm_feeder())
                        group.create_task(_tts_worker())'''
    assert old in body
    body = body.replace(old,new,1)
    body = body.replace('''                except Exception:
                    log.exception("Producer failed")''','''                except Exception:
                    log.exception("Producer failed")
                    raise''',1)
    # 取消路径不能在已停止的消费者前无限等待 sentinel。
    body = body.replace('                    await audio_q.put(None)  # signal done',
        '                    if not asyncio.current_task().cancelling():\n                        await audio_q.put(None)  # signal done',1)
    body = body.replace('                        await sentence_q.put(None)\n',
        '                        if not asyncio.current_task().cancelling():\n                            await sentence_q.put(None)\n',1)
    body = body.replace('                await asyncio.gather(_producer(), _consumer())',
        '                async with asyncio.TaskGroup() as group:\n                    group.create_task(_producer())\n                    group.create_task(_consumer())',1)
    # 保持 speech active 直到队列排空；只说明服务器发完，不伪称手机已播放。
    anchor = '                await self._queue_fasterliveportrait_rest_frame()'
    assert anchor in body
    body = body.replace(anchor,anchor+'''
                if external_text is not None and self.webrtc:
                    async with asyncio.timeout(30):
                        while not self._interrupt.is_set() and (
                            not self.webrtc.audio._queue.empty() or not self.webrtc.video._queue.empty()
                        ):
                            await asyncio.sleep(0.02)
                    await asyncio.sleep(0.04)
''',1)
    # 字幕带轮次，Java 可拒收迟到事件。
    body = body.replace('{"session_id": self.session_id, "text": text, "is_final": False}',
        '{"session_id": self.session_id, "text": text, "is_final": False, "utterance_id": getattr(self, "_active_command_id", None)}',1)
    s = head + body
    s = s.replace('    async def interrupt(self) -> None:\n        self._interrupt.set()',
        '    async def interrupt(self) -> None:\n        external = getattr(self, "_external_round", None)\n        if external is not None:\n            external.cancel()\n        self._interrupt.set()',1)
    p.write_text(s)

p = root / 'apps/api/routes/sessions.py'
s=p.read_text()
old = '''    await session_service.interrupt(r, session_id)
    return {"session_id": session_id, "status": "interrupted"}'''
new = '''    # unified: 打断完成后才确认，且与 begin/断线清理共用同一轮次锁。
    runners = getattr(request.app.state, "session_runners", {})
    runner = runners.get(session_id) if isinstance(runners, dict) else None
    if runner is not None:
        target = getattr(runner, "_external_round", None)
        lock = getattr(runner, "_external_begin_lock", None)
        if lock is None:
            lock = runner._external_begin_lock = asyncio.Lock()
        async with lock:
            if getattr(runner, "_external_round", None) is target:
                await runner.interrupt()
    else:
        await session_service.interrupt(r, session_id)
    return {"session_id": session_id, "status": "interrupted"}'''
if '# unified: 打断完成后才确认' not in s and old in s:
    s=s.replace(old,new,1)
if '/text_stream' not in s:
    s+='''

@router.websocket("/{session_id}/text_stream")
async def text_stream_ws(websocket: WebSocket, session_id: str) -> None:
    """外部 Agent 增量文本：单轮单连接，复用会话内 TTS、渲染与 WebRTC。"""
    from opentalking.pipeline.speak.external_text import external_text_socket
    await external_text_socket(websocket, session_id)
'''
p.write_text(s)

# 每次部署校验全部修改文件，不能只校验渲染客户端。
import py_compile
for name in ['opentalking/providers/synthesis/flashhead/ws_client.py',
             'opentalking/pipeline/speak/synthesis_runner.py', 'apps/api/routes/sessions.py']:
    py_compile.compile(str(root / name), doraise=True)

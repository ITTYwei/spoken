"""外部文本轮次协议；由 unified API 与会话 runner 共用，禁止逐段调用 /say。"""
from __future__ import annotations

import asyncio
import hashlib
import json


class ExternalTextRound:
    """功能：校验顺序、去重并对文本生产方施加有界背压。

    运行过程：<pre>append(seq) -> validate -> Queue(16) -> sentence/TTS</pre>
    案例：begin(0), append(1, 'Hello.'), end(2) -> 只消费一次 Hello.。
    设计要点：<ul><li>重复序号必须具有相同内容。</li>
    <li>取消事件唤醒阻塞写入；错误和断线不被当成正常结束。</li></ul>
    """

    def __init__(self, reply_id: str):
        self.reply_id = reply_id  # 当前回复的不可复用标识。
        self.queue = asyncio.Queue(maxsize=16)  # 最多缓存 16 个文本片段。
        self.cancelled = asyncio.Event()  # 高优先级取消，不排在文本队列后面。
        self.receipts = {}  # 有界轮次内的序号摘要，用于严格重复校验。
        self.next_seq = 1  # begin 固定占用序号 0。
        self.ended = False  # 文本结束，与媒体播放结束分别记录。
        self.chars = 0  # 单轮总字符预算，避免无限流占用内存。

    async def accept(self, message: dict) -> str:
        if message.get('replyId') != self.reply_id or self.cancelled.is_set():
            raise ValueError('stale_reply')
        seq = message.get('seq')
        if type(seq) is not int or not 1 <= seq <= 4096:
            raise ValueError('invalid_seq')
        kind = message.get('type')
        if kind not in ('append', 'end', 'cancel'):
            raise ValueError('invalid_type')
        # cancel 绕过背压，但仍须使用当前轮次和下一个序号。
        digest = hashlib.sha256(json.dumps(message, sort_keys=True).encode()).digest()
        if seq in self.receipts:
            if self.receipts[seq] != digest:
                raise ValueError('conflicting_duplicate')
            return 'duplicate'
        if seq != self.next_seq:
            raise ValueError('out_of_order')
        if kind == 'cancel':
            self.cancel()
        else:
            if self.ended:
                raise ValueError('text_already_ended')
            text = message.get('text') if kind == 'append' else None
            if kind == 'append' and (not isinstance(text, str) or not 0 < len(text) <= 2048):
                raise ValueError('invalid_text')
            if self.chars + len(text or '') > 32768:
                raise ValueError('reply_too_large')
            put = asyncio.create_task(self.queue.put(text))
            stop = asyncio.create_task(self.cancelled.wait())
            try:
                done, _ = await asyncio.wait((put, stop), timeout=15, return_when=asyncio.FIRST_COMPLETED)
                if stop in done or self.cancelled.is_set():
                    raise ValueError('stale_reply')
                if put not in done:
                    raise ValueError('backpressure_timeout')
            finally:
                for task in (put, stop):
                    task.cancel()
                await asyncio.gather(put, stop, return_exceptions=True)
            self.chars += len(text or '')
            self.ended = kind == 'end'
        self.receipts[seq] = digest
        self.next_seq += 1
        return 'accepted'

    def cancel(self):
        self.cancelled.set()
        while not self.queue.empty():
            self.queue.get_nowait()

    async def pieces(self):
        while not self.cancelled.is_set():
            text = await asyncio.wait_for(self.queue.get(), timeout=45)
            if text is None:
                return
            if not self.cancelled.is_set():
                yield text


async def external_text_socket(websocket, session_id: str):
    """一个连接对应一个回复；连接保持到媒体队列发送完，断线立即取消当前回复。"""
    await websocket.accept()
    runners = getattr(websocket.app.state, 'session_runners', {})
    runner = runners.get(session_id)
    round_ = None
    speech = None
    try:
        if runner is None or getattr(runner, 'model_type', '') not in ('flashhead', 'flashtalk'):
            raise ValueError('stream_requires_ready_unified_renderer')
        begin = await asyncio.wait_for(websocket.receive_json(), timeout=10)
        reply_id = begin.get('replyId')
        if begin.get('type') != 'begin' or begin.get('seq') != 0 or not isinstance(reply_id, str) or not 1 <= len(reply_id) <= 128:
            raise ValueError('invalid_begin')
        # 同一会话只允许一个连接拥有当前轮次；旧连接的 finally 不得取消新轮次。
        lock = getattr(runner, '_external_begin_lock', None)
        if lock is None:
            lock = runner._external_begin_lock = asyncio.Lock()
        async with lock:
            seen = getattr(runner, '_external_seen', set())
            runner._external_seen = seen
            if reply_id in seen or len(seen) >= 4096:
                raise ValueError('reused_reply_or_session_limit')
            seen.add(reply_id)
            await runner.interrupt()
            round_ = ExternalTextRound(reply_id)
            runner._external_round = round_
            runner._active_command_id = reply_id
            speech = asyncio.create_task(runner.speak('', tts_voice=begin.get('voice'),
                tts_provider=begin.get('ttsProvider'), direct=True, external_text=round_))
            runner.speech_tasks.add(speech)
            speech.add_done_callback(runner.speech_tasks.discard)
        await websocket.send_json({'type': 'ack', 'replyId': reply_id, 'seq': 0, 'status': 'accepted'})
        while True:
            receive = asyncio.create_task(websocket.receive_json())
            try:
                done, _ = await asyncio.wait((receive, speech), return_when=asyncio.FIRST_COMPLETED)
                if speech in done:
                    await speech
                    await websocket.send_json({'type': 'playback_end', 'replyId': reply_id,
                                               'scope': 'server_media_drained'})
                    break
                message = receive.result()
                status = await round_.accept(message)
                await websocket.send_json({'type': 'ack', 'replyId': reply_id,
                                           'seq': message['seq'], 'status': status})
                if message['type'] == 'cancel':
                    break
            finally:
                receive.cancel()
                await asyncio.gather(receive, return_exceptions=True)
    except Exception as exc:
        # 错误只返回类型或预定义协议码，避免把服务配置泄漏给客户端。
        try:
            await websocket.send_json({'type': 'error', 'code': str(exc) if isinstance(exc, ValueError) else type(exc).__name__})
        except Exception:
            pass
    finally:
        if round_ is not None:
            async with runner._external_begin_lock:
                if getattr(runner, '_external_round', None) is round_ and speech is not None and not speech.done():
                    round_.cancel()
                    await runner.interrupt()
        try:
            await websocket.close()
        except Exception:
            pass

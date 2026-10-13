"""Verify real WebSocket protocol, lease isolation and disconnect draining.

The model is replaced with a small fake; GPU image quality is verified separately
against the deployed Fast-FlashTalk weights and OpenTalking client.
"""

import asyncio
import base64
import io
import json
import struct
import threading
import unittest
from concurrent.futures import ThreadPoolExecutor

import numpy as np
from PIL import Image
from websockets.asyncio.client import connect
from websockets.asyncio.server import serve

from ws_server import FastFlashTalkService, encode_video


class FakeEngine:
    """功能：用可控的执行屏障验证会话隔离，不加载 GPU 权重。

    运行过程：<pre>init -> render waits on barrier -> release</pre>
    案例：推理过程中连接断开，下一会话须等 render 完成和 release 后才能取得租约。
    设计要点：<ul><li>只替代模型计算；测试使用实际 WebSocket 和协议服务。</li></ul>
    """

    def __init__(self):
        # 与实际模型握手一致的固定参数。
        self.config = dict(frame_num=33, motion_frames_num=5, tgt_fps=25,
                           sample_rate=16000, height=16, width=16)
        # 测试观察的操作序列。
        self.operations = []
        # 标记执行线程已开始 render。
        self.started = threading.Event()
        # 允许测试控制 render 何时完成。
        self.proceed = threading.Event()
        self.proceed.set()

    def initialize(self, image, prompt, seed):
        self.operations.append("init")
        if prompt == "fail":
            raise ValueError("initialization failed")

    def render(self, pcm):
        self.started.set()
        if not self.proceed.wait(5):
            raise TimeoutError("test barrier timed out")
        self.operations.append("render")
        return encode_video(np.zeros((28, 16, 16, 3), np.uint8))

    def release(self):
        self.operations.append("release")


class ProtocolTests(unittest.IsolatedAsyncioTestCase):
    """功能：验证坏输入、重复初始化及断开时不会破坏其他会话。

    运行过程：<pre>start local server -> connect clients -> assert responses -> close</pre>
    案例：A 推理时断开，B 收到 busy，A 清理完成后 B 的 init 成功。
    设计要点：<ul><li>用屏障控制推理时序，避免依赖 GPU 速度或任意等待。</li></ul>
    """

    async def asyncSetUp(self):
        self.engine = FakeEngine()
        self.executor = ThreadPoolExecutor(max_workers=1)
        self.service = FastFlashTalkService(self.engine, self.executor)
        self.server = await serve(self.service.handle, "127.0.0.1", 0)
        self.url = "ws://127.0.0.1:" + str(self.server.sockets[0].getsockname()[1])
        out = io.BytesIO()
        Image.new("RGB", (16, 16)).save(out, "PNG")
        self.init = {"type": "init", "ref_image": base64.b64encode(out.getvalue()).decode()}

    async def asyncTearDown(self):
        self.engine.proceed.set()
        self.server.close()
        await self.server.wait_closed()
        self.executor.shutdown()

    async def command(self, ws, data):
        await ws.send(json.dumps(data))
        return json.loads(await ws.recv())

    async def test_handshake_audio_and_close(self):
        async with connect(self.url) as ws:
            result = await self.command(ws, self.init)
            self.assertEqual(result["implementation"], "fast-flashtalk")
            self.assertEqual(result["chunk_samples"], 17920)
            await ws.send(b"AUDI" + bytes(17920 * 2))
            result = await ws.recv()
            self.assertEqual(result[:4], b"VIDX")
            self.assertEqual(struct.unpack("<I", result[4:8])[0], 28)
            jpeg_size = struct.unpack("<I", result[8:12])[0]
            with Image.open(io.BytesIO(result[12:12 + jpeg_size])) as image:
                self.assertEqual(image.size, (16, 16))
            self.assertEqual((await self.command(ws, {"type": "close"}))["type"], "close_ok")
            self.assertFalse(self.service.lease.locked())

    async def test_invalid_input_does_not_acquire_or_corrupt_lease(self):
        async with connect(self.url) as ws:
            await ws.send(b"AUDI")
            self.assertEqual(json.loads(await ws.recv())["type"], "error")
            for data in [[], {"type": "init", "ref_image": "!"}]:
                self.assertEqual((await self.command(ws, data))["type"], "error")
            self.assertFalse(self.service.lease.locked())
            self.assertEqual((await self.command(ws, self.init))["type"], "init_ok")
            self.assertEqual((await self.command(ws, self.init))["type"], "error")
            await ws.send(b"AUDI" + bytes(2))
            self.assertEqual(json.loads(await ws.recv())["type"], "error")
            self.assertNotIn("render", self.engine.operations)

    async def test_failed_init_releases_lease(self):
        async with connect(self.url) as ws:
            failed = dict(self.init, prompt="fail")
            self.assertEqual((await self.command(ws, failed))["type"], "error")
            self.assertFalse(self.service.lease.locked())
            self.assertEqual((await self.command(ws, self.init))["type"], "init_ok")

    async def test_disconnect_drains_before_next_avatar(self):
        async with connect(self.url) as a, connect(self.url) as b:
            self.assertEqual((await self.command(a, self.init))["type"], "init_ok")
            self.engine.proceed.clear()
            await a.send(b"AUDI" + bytes(17920 * 2))
            self.assertTrue(await asyncio.to_thread(self.engine.started.wait, 2))
            self.assertIn("model_busy", (await self.command(b, self.init))["message"])
            await a.close()
            reinitialize = asyncio.create_task(self.command(b, self.init))
            await asyncio.sleep(0.02)
            self.assertFalse(reinitialize.done())
            self.engine.proceed.set()
            self.assertEqual((await asyncio.wait_for(reinitialize, 3))["type"], "init_ok")
            self.assertEqual(self.engine.operations[-3:], ["render", "release", "init"])


if __name__ == "__main__":
    unittest.main()

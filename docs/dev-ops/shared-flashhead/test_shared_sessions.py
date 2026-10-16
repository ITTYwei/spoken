import asyncio
import concurrent.futures
import threading
import unittest
from types import SimpleNamespace

from shared_sessions import SessionContext, SessionRegistry, run_serialized


class ContextTests(unittest.TestCase):
    def test_weights_shared_but_session_fields_independent(self):
        weights = SimpleNamespace(model=object(), vae=object(), audio_encoder=object())
        first = SessionContext.create(weights, {"width": 512})
        second = SessionContext.create(weights, {"width": 512})
        first.pipeline.motion = [1]
        second.pipeline.motion = [2]
        first.params["width"] = 768
        self.assertIs(first.pipeline.model, second.pipeline.model)
        self.assertIs(first.pipeline.vae, second.pipeline.vae)
        self.assertIs(first.pipeline.audio_encoder, second.pipeline.audio_encoder)
        self.assertEqual(second.pipeline.motion, [2])
        self.assertEqual(second.params["width"], 512)
        self.assertFalse(hasattr(weights, "motion"))

    def test_capacity_and_idempotent_release(self):
        registry = SessionRegistry(2)
        first, second = registry.reserve(), registry.reserve()
        self.assertNotEqual(first, second)
        with self.assertRaises(RuntimeError):
            registry.reserve()
        registry.release(first)
        registry.release(first)
        registry.reserve()
        self.assertEqual(len(registry.sessions), 2)


class CancellationTests(unittest.IsolatedAsyncioTestCase):
    async def test_cancelled_inference_keeps_lock_until_thread_finishes(self):
        started, finish = threading.Event(), threading.Event()
        lock = asyncio.Lock()
        executor = concurrent.futures.ThreadPoolExecutor(max_workers=1)

        def work():
            started.set()
            finish.wait(5)

        try:
            task = asyncio.create_task(run_serialized(lock, executor, work))
            deadline = asyncio.get_running_loop().time() + 3
            while not started.is_set():
                if task.done():
                    await task
                if asyncio.get_running_loop().time() > deadline:
                    self.fail("executor did not start")
                await asyncio.sleep(0.001)
            task.cancel()
            await asyncio.sleep(0)
            self.assertTrue(lock.locked())
            task.cancel()
            await asyncio.sleep(0)
            self.assertTrue(lock.locked())
            finish.set()
            with self.assertRaises(asyncio.CancelledError):
                await task
            self.assertFalse(lock.locked())
        finally:
            finish.set()
            executor.shutdown(wait=True)


if __name__ == "__main__":
    unittest.main()

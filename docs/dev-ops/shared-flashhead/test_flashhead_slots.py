"""Run inside the OpenTalking checkout with pytest."""
import asyncio
from pathlib import Path

import pytest

from opentalking.core.in_memory_redis import InMemoryRedis
import opentalking.runtime.task_consumer as consumer


class Runner:
    _closed = False

    async def close(self):
        self._closed = True


@pytest.mark.asyncio
async def test_two_active_third_queued_cancel_and_reuse(monkeypatch):
    monkeypatch.setenv("OPENTALKING_FLASHHEAD_MAX_SESSIONS", "2")
    monkeypatch.setattr(consumer, "_flashhead_slot_pool", None)
    monkeypatch.setattr(consumer, "_slot_queue_size", 0)
    monkeypatch.setattr(consumer, "_queued_tasks", {})
    monkeypatch.setattr(consumer, "_create_runner", lambda *a: Runner())
    redis, runners = InMemoryRedis(), {}

    async def init(task, _redis, _root, _device, registry, sid):
        registry[sid] = Runner()

    monkeypatch.setattr(consumer, "_do_init", init)
    tasks = []

    async def start(sid):
        task = asyncio.create_task(consumer._init_flashtalk_with_queue(
            {"model": "flashhead"}, redis, Path("."), "cpu", runners, sid))
        tasks.append(task)
        await asyncio.sleep(0.02)

    try:
        await start("a")
        await start("b")
        assert set(runners) == {"a", "b"}
        assert consumer.flashhead_slot_is_occupied()
        assert not consumer.slot_is_occupied()
        await start("c")
        assert "c" not in runners
        assert consumer.slot_queue_size() == 1
        # A non-close command must not orphan the queued cancellation handle.
        await consumer.handle_worker_task({"cmd": "interrupt", "session_id": "c"},
                                          redis, Path("."), "cpu", runners)
        await consumer.handle_worker_task({"cmd": "close", "session_id": "c"},
                                          redis, Path("."), "cpu", runners)
        await asyncio.sleep(0.02)
        assert consumer.slot_queue_size() == 0
        runners["a"]._closed = True
        await asyncio.sleep(0.55)
        await start("d")
        assert set(runners) == {"b", "d"}
    finally:
        runners.clear()
        for task in tasks:
            task.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)


@pytest.mark.asyncio
async def test_failed_prepare_returns_permit(monkeypatch):
    monkeypatch.setenv("OPENTALKING_FLASHHEAD_MAX_SESSIONS", "2")
    monkeypatch.setattr(consumer, "_flashhead_slot_pool", None)
    monkeypatch.setattr(consumer, "_slot_queue_size", 0)
    monkeypatch.setattr(consumer, "_queued_tasks", {})

    async def fail(*args):
        raise RuntimeError("prepare failed")

    monkeypatch.setattr(consumer, "_do_init", fail)
    with pytest.raises(RuntimeError, match="prepare failed"):
        await consumer._init_flashtalk_with_queue({"model": "flashhead"}, InMemoryRedis(),
                                                 Path("."), "cpu", {}, "failed")
    assert consumer.slot_queue_size() == 0
    assert not consumer.flashhead_slot_is_occupied()
    assert "failed" not in consumer._queued_tasks

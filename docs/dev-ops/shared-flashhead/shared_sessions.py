"""Session shells share immutable model weights, never evolving inference state."""
import asyncio
import copy
import uuid
from dataclasses import dataclass


@dataclass
class SessionContext:
    pipeline: object
    params: dict
    cpu_rng: object = None
    cuda_rng: object = None

    @classmethod
    def create(cls, weights, params):
        # The template must never prepare a reference image or generate frames.
        # prepare_params replaces every session-owned container on this shell.
        return cls(copy.copy(weights), dict(params))


class SessionRegistry:
    def __init__(self, capacity):
        if capacity < 1:
            raise ValueError("capacity must be positive")
        self.capacity = capacity
        self.sessions = set()

    def reserve(self):
        if len(self.sessions) >= self.capacity:
            raise RuntimeError("session capacity reached")
        sid = uuid.uuid4().hex
        self.sessions.add(sid)
        return sid

    def release(self, sid):
        self.sessions.discard(sid)


async def run_serialized(lock, executor, function, *args):
    """A cancelled socket cannot release the GPU lock before its thread finishes."""
    async with lock:
        future = asyncio.get_running_loop().run_in_executor(executor, function, *args)
        try:
            return await asyncio.shield(future)
        except asyncio.CancelledError:
            # Executor work cannot be cancelled. Drain it before unlocking, even
            # if shutdown delivers another cancellation to the awaiting task.
            while not future.done():
                try:
                    await asyncio.shield(future)
                except asyncio.CancelledError:
                    continue
                except Exception:
                    break
            if future.done() and not future.cancelled():
                future.exception()
            raise

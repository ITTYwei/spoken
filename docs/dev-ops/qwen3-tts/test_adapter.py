"""Run with OpenTalking's Python and repository on PYTHONPATH (Python <=3.12)."""
import audioop
import asyncio
import json
import unittest
from unittest.mock import patch

import httpx
import numpy as np

from opentalking.providers.tts.openai_compatible.adapter import OpenAICompatibleTTSAdapter


class FragmentedAudio(httpx.AsyncByteStream):
    def __init__(self, data):
        self.data = data

    async def __aiter__(self):
        # Deliberately split samples across HTTP packets.
        for offset in range(0, len(self.data), 137):
            yield self.data[offset:offset + 137]


class AdapterTest(unittest.TestCase):
    def test_streaming_resampling(self):
        async def run():
            raw = (np.sin(np.arange(24000) * 2 * np.pi * 440 / 24000) * 12000).astype("<i2").tobytes()
            requests = []

            async def handler(request):
                requests.append(json.loads(request.content))
                return httpx.Response(200, stream=FragmentedAudio(raw))

            original = httpx.AsyncClient

            def client(**kwargs):
                return original(transport=httpx.MockTransport(handler), **kwargs)

            adapter = OpenAICompatibleTTSAdapter(
                api_key="local", base_url="http://localhost/v1", model="qwen3-tts",
                default_voice="ryan", response_format="pcm", protocol="vllm_omni",
                sample_rate=16000,
            )
            with patch("httpx.AsyncClient", client):
                chunks = [part async for part in adapter.synthesize_stream("Hello")]
            audio = b"".join(c.data.astype("<i2").tobytes() for c in chunks)
            expected, _ = audioop.ratecv(raw, 2, 1, 24000, 16000, None)
            self.assertEqual(audio, expected)
            self.assertTrue(all(c.sample_rate == 16000 for c in chunks))
            self.assertEqual(requests[0]["stream"], True)
            self.assertEqual(requests[0]["stream_format"], "audio")
            self.assertEqual(requests[0]["voice"], "ryan")
            self.assertAlmostEqual(sum(c.duration_ms for c in chunks), 1000, delta=1)

        asyncio.run(run())


if __name__ == "__main__":
    unittest.main()

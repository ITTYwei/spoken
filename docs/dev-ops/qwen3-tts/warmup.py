"""Wait for the local API and finish a real inference before service readiness."""
import time

import httpx


def main():
    with httpx.Client(timeout=180) as client:
        deadline = time.monotonic() + 160
        while True:
            try:
                response = client.get("http://127.0.0.1:8091/health", timeout=2)
                response.raise_for_status()
                break
            except httpx.HTTPError:
                if time.monotonic() >= deadline:
                    raise
                time.sleep(1)
        response = client.post("http://127.0.0.1:8091/v1/audio/speech", json={
            "model": "qwen3-tts", "input": "Hello!", "voice": "ryan",
            "language": "English", "response_format": "wav",
        })
        response.raise_for_status()
        if len(response.content) < 1000 or response.content[:4] != b"RIFF":
            raise RuntimeError("Warmup did not produce WAV audio")
        print("Qwen3-TTS warmup completed", flush=True)


if __name__ == "__main__":
    main()

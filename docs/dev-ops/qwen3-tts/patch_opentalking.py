"""Add opt-in vllm_omni streaming to the existing compatible TTS adapter.

Run on the OpenTalking host; saves an original copy before an exact patch.
"""
import argparse
from pathlib import Path


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo", type=Path, required=True)
    args = parser.parse_args()
    path = args.repo / "opentalking/providers/tts/openai_compatible/adapter.py"
    source = path.read_text()
    if 'self.protocol == "vllm_omni"' in source:
        print("vllm_omni patch already applied")
        return
    old_payload = '                url = f"{self.base_url}/audio/speech"'
    new_payload = '''                if self.protocol == "vllm_omni":
                    payload.update(stream=True, stream_format="audio", language="Auto")
                url = f"{self.base_url}/audio/speech"'''
    old_buffer = '''                        buf = bytearray()
                        async for part in resp.aiter_bytes():
                            if not part:
                                continue
                            buf.extend(part)'''
    new_buffer = '''                        buf = bytearray()
                        resample_state = None
                        raw_tail = b""
                        async for part in resp.aiter_bytes():
                            if not part:
                                continue
                            if self.protocol == "vllm_omni":
                                # vLLM Qwen3-TTS emits mono signed PCM16 at 24 kHz.
                                # Retain both byte alignment and filter state across packets.
                                import audioop
                                raw = raw_tail + part
                                aligned = len(raw) - len(raw) % 2
                                raw_tail = raw[aligned:]
                                part, resample_state = audioop.ratecv(
                                    raw[:aligned], 2, 1, 24000, self.sample_rate,
                                    resample_state,
                                )
                            buf.extend(part)'''
    for before in (old_payload, old_buffer):
        if source.count(before) != 1:
            raise RuntimeError("Adapter changed; refusing to apply an ambiguous patch")
    backup = path.with_suffix(".py.before-qwen3-vllm")
    if backup.exists():
        raise RuntimeError("Backup already exists; inspect adapter before applying")
    backup.write_text(source)
    source = source.replace(old_payload, new_payload).replace(old_buffer, new_buffer)
    source = source.replace(
        '        headers = {',
        '''        if self.protocol == "vllm_omni":
            if self.response_format != "pcm":
                raise RuntimeError("vllm_omni protocol requires response_format=pcm")
            # Preserve previously configured preset selections during migration.
            aliases = {"cherry": "vivian", "serena": "serena", "ethan": "ryan",
                       "chelsie": "vivian", "dylan": "aiden", "jada": "serena",
                       "roy": "ryan"}
            effective_voice = aliases.get(effective_voice.lower(), effective_voice.lower())
        headers = {''',
        1,
    )
    path.write_text(source)
    print("Patched", path)


if __name__ == "__main__":
    main()

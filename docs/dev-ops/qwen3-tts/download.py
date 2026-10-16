"""Download the preset-voice model and audio tokenizer using ModelScope."""
import argparse
from pathlib import Path

from modelscope import snapshot_download


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--model-root", type=Path, required=True)
    args = parser.parse_args()
    for name in ("Qwen3-TTS-12Hz-0.6B-CustomVoice", "Qwen3-TTS-Tokenizer-12Hz"):
        snapshot_download("Qwen/" + name, local_dir=str(args.model_root / name))


if __name__ == "__main__":
    main()

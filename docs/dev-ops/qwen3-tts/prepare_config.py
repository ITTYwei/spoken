"""Derive a single-request configuration from the installed Omni release."""
import argparse
import importlib.util
from pathlib import Path

import yaml


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--stage-memory", type=float, default=0.12)
    args = parser.parse_args()
    if not 0 < args.stage_memory < 0.5:
        parser.error("stage-memory must be between 0 and 0.5 (per stage)")
    spec = importlib.util.find_spec("vllm_omni")
    root = Path(next(iter(spec.submodule_search_locations)))
    config = yaml.safe_load((root / "deploy" / "qwen3_tts.yaml").read_text())
    for stage in config["stages"]:
        stage["max_num_seqs"] = 1
        stage["gpu_memory_utilization"] = args.stage_memory
        stage["enforce_eager"] = True
    args.output.write_text(yaml.safe_dump(config, sort_keys=False))


if __name__ == "__main__":
    main()

"""Write a user-level systemd service; enable it with systemctl --user."""
import argparse
from pathlib import Path


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--model-root", type=Path, required=True)
    args = parser.parse_args()
    root = args.root.resolve()
    models = args.model_root.resolve()
    # Keep systemd command and Environment syntax unambiguous.
    for path in (root, models):
        if any(c in str(path) for c in ' \n\r"%\\'):
            parser.error("Use paths without spaces, quotes, percent signs or backslashes")
    target = Path.home() / ".config/systemd/user/qwen3-tts.service"
    target.parent.mkdir(parents=True, exist_ok=True)
    body = f"""[Unit]
Description=Qwen3-TTS 0.6B via vLLM-Omni
After=network-online.target

[Service]
Type=exec
WorkingDirectory={root}
Environment=TTS_MODEL_ROOT={models}
ExecStart=/usr/bin/bash {root}/start.sh
ExecStartPost={root}/.venv/bin/python {root}/warmup.py
Restart=on-failure
RestartSec=10
TimeoutStartSec=300
TimeoutStopSec=45
KillMode=control-group

[Install]
WantedBy=default.target
"""
    if target.exists() and target.read_text() != body:
        backup = target.with_suffix(".service.before-qwen3")
        if backup.exists():
            raise RuntimeError("Service backup already exists; inspect before overwriting")
        backup.write_text(target.read_text())
    target.write_text(body)
    print(target)


if __name__ == "__main__":
    main()

"""Stop only the two isolated instances created for this experiment."""
import os
import signal
from pathlib import Path

ROOT = Path("/home/nm/develop/code/experiments/shared-flashhead-v2")
for pid in (int((ROOT / "api-test.pid").read_text()), 2057111):
    proc = Path(f"/proc/{pid}")
    if not proc.exists():
        continue
    argv = [os.fsdecode(x) for x in (proc / "cmdline").read_bytes().split(b"\0") if x]
    cwd = str((proc / "cwd").resolve())
    if pid == 2057111:
        assert argv == ["/home/nm/miniconda3/envs/flashhead/bin/python", "flashhead_server.py"], argv
        assert cwd == "/home/nm/develop/code/backup/SoulX-FlashHead", cwd
    else:
        assert cwd == str(ROOT / "opentalking"), cwd
        assert argv[-1] == "18001" and "apps.unified.main" in argv, argv
    os.kill(pid, signal.SIGTERM)
    print("Stopped isolated test process", pid)

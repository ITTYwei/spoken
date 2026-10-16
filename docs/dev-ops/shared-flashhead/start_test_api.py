"""Start an isolated API using the existing runtime without exposing credentials."""
import os
import socket
import subprocess
from pathlib import Path

ROOT = Path("/home/nm/develop/code/experiments/shared-flashhead-v2")
with socket.socket() as sock:
    if sock.connect_ex(("127.0.0.1", 18001)) == 0:
        raise RuntimeError("test port already in use")
env = os.environ.copy()
# Reuse only the existing API process environment, without displaying it.
for item in Path("/proc/1152939/environ").read_bytes().split(b"\0"):
    if b"=" in item:
        key, value = item.split(b"=", 1)
        env[os.fsdecode(key)] = os.fsdecode(value)
env.update(OPENTALKING_FLASHHEAD_MAX_SESSIONS="2",
           OPENTALKING_FLASHHEAD_WS_URL="ws://127.0.0.1:18767/v1/avatar/realtime")
with (ROOT / "api-test.log").open("ab") as log:
    process = subprocess.Popen(
        [str(ROOT / "opentalking/.venv/bin/python"), "-m", "apps.unified.main",
         "--host", "127.0.0.1", "--port", "18001"],
        cwd=ROOT / "opentalking", env=env, stdin=subprocess.DEVNULL,
        stdout=log, stderr=log, start_new_session=True,
    )
(ROOT / "api-test.pid").write_text(str(process.pid))
print("isolated API PID", process.pid)

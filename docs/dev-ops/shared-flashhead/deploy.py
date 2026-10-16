"""Scoped deployment / rollback. Private launch metadata stays on the GPU host."""
import argparse
import hashlib
import json
import os
import shutil
import signal
import subprocess
import time
from pathlib import Path

BASE = Path("/home/nm/develop/code")
TEST = BASE / "experiments/shared-flashhead-v2"
API = BASE / "opentalking-main"
BRIDGE = BASE / "spokenai-bridge"
BACKUP = BASE / "backups/shared-flashhead-v2-predeploy"
FLAG = "OPENTALKING_FLASHHEAD_MAX_SESSIONS"
FILES = [API / "opentalking/runtime/task_consumer.py", API / "apps/api/routes/sessions.py",
         BRIDGE / "flashhead_server.py"]


def process(pid):
    proc = Path(f"/proc/{pid}")
    env = {}
    for item in (proc / "environ").read_bytes().split(b"\0"):
        if b"=" in item:
            key, value = item.split(b"=", 1)
            env[os.fsdecode(key)] = os.fsdecode(value)
    return {"pid": pid, "cwd": str((proc / "cwd").resolve()), "env": env,
            "argv": [os.fsdecode(x) for x in (proc / "cmdline").read_bytes().split(b"\0") if x]}


def stop(info):
    pid = info["pid"]
    if not Path(f"/proc/{pid}").exists():
        return
    current = process(pid)
    if current["argv"] != info["argv"] or current["cwd"] != info["cwd"]:
        raise RuntimeError(f"PID {pid} no longer belongs to expected service")
    os.kill(pid, signal.SIGTERM)
    for _ in range(100):
        if not Path(f"/proc/{pid}").exists():
            return
        # A zombie has exited and no longer holds ports or GPU allocations.
        if Path(f"/proc/{pid}/stat").read_text().split()[2] == "Z":
            return
        time.sleep(0.1)
    raise RuntimeError(f"service {pid} did not stop; not forcing termination")


def launch(info, label, concurrency):
    env = dict(info["env"])
    if label == "api":
        if concurrency:
            env[FLAG] = "2"
        else:
            env.pop(FLAG, None)
    with (BASE / "logs" / f"{label}-shared-v2.log").open("ab") as log:
        child = subprocess.Popen(info["argv"], cwd=info["cwd"], env=env,
                                 stdin=subprocess.DEVNULL, stdout=log, stderr=log,
                                 start_new_session=True)
    result = dict(info, pid=child.pid)
    if label == "api":
        (BASE / "run/opentalking-api-8001.pid").write_text(str(child.pid))
    return result


def set_flag(value):
    path = API / ".env"
    lines = path.read_text().splitlines() if path.exists() else []
    lines = [line for line in lines if not line.strip().startswith(FLAG + "=")]
    if value is not None:
        lines.append(FLAG + "=" + value)
    path.write_text("\n".join(lines) + "\n")


def save(name, value):
    path = BACKUP / name
    path.write_text(json.dumps(value))
    path.chmod(0o600)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("action", choices=["prepare", "install", "rollback"])
    args = parser.parse_args()
    if args.action == "prepare":
        BACKUP.mkdir(mode=0o700, exist_ok=False)
        manifest = {"api": process(1152939), "bridge": process(2920357), "files": []}
        assert manifest["api"]["cwd"] == str(API)
        for index, path in enumerate(FILES):
            target = BACKUP / f"original-{index}.py"
            shutil.copy2(path, target)
            manifest["files"].append({"path": str(path), "backup": str(target),
                                      "sha256": hashlib.sha256(path.read_bytes()).hexdigest()})
        env_path = API / ".env"
        manifest["previous_flag"] = None
        for line in env_path.read_text().splitlines():
            if line.strip().startswith(FLAG + "="):
                manifest["previous_flag"] = line.split("=", 1)[1]
        save("original.json", manifest)
        print("Prepared scoped rollback:", BACKUP)
        return
    manifest = json.loads((BACKUP / "original.json").read_text())
    if args.action == "install":
        for item in manifest["files"]:
            if hashlib.sha256(Path(item["path"]).read_bytes()).hexdigest() != item["sha256"]:
                raise RuntimeError("source changed since prepare; refusing to overwrite")
        # Stop only the two isolated test instances, verifying process identity.
        for pid in (int((TEST / "api-test.pid").read_text()), 2057111):
            if Path(f"/proc/{pid}").exists():
                info = process(pid)
                if not info["cwd"].startswith(str(TEST)) and pid != 2057111:
                    raise RuntimeError("test process changed")
                if pid == 2057111 and info["argv"][-1] != "flashhead_server.py":
                    raise RuntimeError("test bridge identity changed")
                stop(info)
        stop(manifest["api"])
        stop(manifest["bridge"])
        try:
            shutil.copyfile(TEST / "opentalking/opentalking/runtime/task_consumer.py", FILES[0])
            # Patch the current API file, preserving all unrelated changes.
            subprocess.run(["python3", str(TEST / "patch_api.py"), str(API)], check=True)
            for name in ("flashhead_server.py", "shared_sessions.py"):
                shutil.copyfile(TEST / "bridge" / name, BRIDGE / name)
            set_flag("2")
            state = {}
            save("running.json", state)
            state["bridge"] = launch(manifest["bridge"], "bridge", True)
            save("running.json", state)
            state["api"] = launch(manifest["api"], "api", True)
            save("running.json", state)
            print("Installed shared model capacity=2; PIDs", state["bridge"]["pid"], state["api"]["pid"])
        except BaseException:
            # Recovery remains available even if deployment is interrupted.
            print("Deployment failed. Run deploy.py rollback immediately.")
            raise
    else:
        state_path = BACKUP / "running.json"
        if state_path.exists():
            state = json.loads(state_path.read_text())
            for label in ("api", "bridge"):
                if label in state:
                    stop(state[label])
        for item in manifest["files"]:
            shutil.copyfile(item["backup"], item["path"])
        set_flag(manifest["previous_flag"])
        state = {"bridge": launch(manifest["bridge"], "bridge", False),
                 "api": launch(manifest["api"], "api", False)}
        save("rollback-running.json", state)
        print("Restored predeployment source and startup environment; PIDs",
              state["bridge"]["pid"], state["api"]["pid"])


if __name__ == "__main__":
    main()

"""Run a GPU benchmark with bounded runtime and memory protection.

Process: sample resources -> run only the supplied child -> save measurements.
Example: low available RAM terminates the benchmark, leaving existing services.
Only the child process group created by this script is eligible for termination.
"""

import argparse
import json
import os
import signal
import subprocess
import time
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True)
    parser.add_argument("--timeout", type=int, default=900)
    parser.add_argument("command", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    command = args.command[1:] if args.command[:1] == ["--"] else args.command
    output = Path(args.output)
    output.mkdir(parents=True, exist_ok=True)
    report = {"command": command, "samples": []}
    start = time.monotonic()
    with (output / "run.log").open("wb") as log:
        child = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
        report["pid"] = child.pid
        print("BENCHMARK_PID", child.pid, flush=True)
        while child.poll() is None:
            mem = {line.split(":", 1)[0]: int(line.split()[1]) for line in Path("/proc/meminfo").read_text().splitlines()}
            gpu = subprocess.check_output(
                ["nvidia-smi", "--query-gpu=memory.used,memory.total", "--format=csv,noheader,nounits"], text=True
            ).strip().split(",")
            used, total = (int(value.strip()) for value in gpu)
            sample = {
                "seconds": round(time.monotonic() - start, 1),
                "available_ram_mib": round(mem["MemAvailable"] / 1024),
                "gpu_used_mib": used,
                "gpu_total_mib": total,
            }
            report["samples"].append(sample)
            reason = None
            if sample["available_ram_mib"] < 4096:
                reason = "Available system RAM dropped below 4 GiB"
            elif total - used < 512:
                reason = "Available GPU memory dropped below 512 MiB"
            elif sample["seconds"] > args.timeout:
                reason = "Benchmark runtime limit reached"
            if reason:
                report["stopped_reason"] = reason
                os.killpg(child.pid, signal.SIGTERM)
                try:
                    child.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    os.killpg(child.pid, signal.SIGKILL)
                    child.wait()
                print("BENCHMARK_STOPPED", reason, flush=True)
                break
            if len(report["samples"]) % 4 == 1:
                print("RESOURCE", json.dumps(sample), flush=True)
            time.sleep(5)
        report["exit_code"] = child.wait()
    report["elapsed_seconds"] = round(time.monotonic() - start, 1)
    (output / "resources.json").write_text(json.dumps(report, indent=2))
    print("BENCHMARK_EXIT", report["exit_code"], flush=True)
    print((output / "run.log").read_text(errors="replace")[-4500:], flush=True)
    raise SystemExit(0 if report["exit_code"] == 0 else 1)


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Reproducible benchmark harness for the MCP server (stdio, JSON-RPC).

Design notes:
  * Drives the PACKAGED jar over stdio exactly like a real client.
  * Every scenario starts from a fresh process so configuration (limits, timeouts)
    is not shared between measurements.
  * One reader thread dispatches responses by JSON-RPC id; N writer threads issue
    calls, so latency is per-call and throughput is wall-clock.
  * The rate limiter is deliberately raised for the load scenarios: it exists to
    protect CPU-heavy tools, and leaving it at 30 calls / 10 per second would
    measure the limiter, not the server.

Usage:
  python3 bench/run_bench.py --jar target/mcp-dev-tooling-0.1.0.jar --out bench/results
  python3 bench/run_bench.py --help
"""
import argparse
import json
import os
import platform
import queue
import shutil
import socket
import statistics
import subprocess
import sys
import tempfile
import threading
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

PROTOCOL_VERSION = "2025-11-25"


# ----------------------------------------------------------------------------- client

class StdioClient:
    def __init__(self, jar, env, cwd=None):
        self.process = subprocess.Popen(
            [shutil.which("java") or "java", "-jar", str(jar)],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            env=env, cwd=cwd, text=True, encoding="utf-8", bufsize=1)
        self._lock = threading.Lock()
        self._write_lock = threading.Lock()
        self._responses = {}
        self._event = threading.Condition(self._lock)
        self._reader = threading.Thread(target=self._pump, daemon=True)
        self._reader.start()
        self._next_id = 0

    def _pump(self):
        for line in self.process.stdout:
            line = line.strip()
            if not line:
                continue
            try:
                message = json.loads(line)
            except json.JSONDecodeError:
                # Protocol purity is asserted by the test suite; here a stray line
                # would corrupt latency numbers, so surface it.
                raise SystemExit("non-JSON line on stdout: %r" % line)
            if "id" in message and ("result" in message or "error" in message):
                with self._event:
                    self._responses[message["id"]] = message
                    self._event.notify_all()
        with self._event:
            self._event.notify_all()

    def _send(self, message):
        with self._write_lock:
            self.process.stdin.write(json.dumps(message) + "\n")
            self.process.stdin.flush()

    def send_notification(self, method, params):
        self._send({"jsonrpc": "2.0", "method": method, "params": params})

    def next_id(self):
        with self._lock:
            self._next_id += 1
            return self._next_id

    def call(self, method, params=None, timeout=60.0, request_id=None):
        request_id = self.next_id() if request_id is None else request_id
        message = {"jsonrpc": "2.0", "id": request_id, "method": method}
        if params is not None:
            message["params"] = params
        self._send(message)
        return self.wait(request_id, timeout)

    def wait(self, request_id, timeout=60.0):
        deadline = time.monotonic() + timeout
        with self._event:
            while request_id not in self._responses:
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise TimeoutError("no response for id %s" % request_id)
                self._event.wait(remaining)
            return self._responses.pop(request_id)

    def initialize(self):
        result = self.call("initialize", {
            "protocolVersion": PROTOCOL_VERSION,
            "capabilities": {},
            "clientInfo": {"name": "bench", "version": "1"},
        })
        self.send_notification("notifications/initialized", {})
        return result

    def close(self):
        try:
            self.process.stdin.close()
        except OSError:
            pass
        try:
            self.process.wait(timeout=15)
        except subprocess.TimeoutExpired:
            self.process.kill()
            self.process.wait()
        stderr = self.process.stderr.read()
        if self.process.returncode not in (0, None):
            sys.stderr.write("server exit %s\n%s\n" % (self.process.returncode, stderr[-4000:]))


def base_env(audit_file, roots, extra=None):
    env = dict(os.environ)
    env.update({
        "MCP_ROLE": "DEVELOPER",
        "MCP_FS_ROOTS": str(roots),
        "MCP_AUDIT_FILE": str(audit_file),
        "MCP_RATE_LIMIT_CAPACITY": "1000000",
        "MCP_RATE_LIMIT_REFILL": "1000000",
        "MCP_DB_URL": "",
    })
    env.update(extra or {})
    return env


def db_env(url, user, password, pool=5):
    return {
        "MCP_DB_URL": url,
        "MCP_DB_USER": user,
        "MCP_DB_PASSWORD": password,
        "MCP_DB_MAX_POOL_SIZE": str(pool),
    }


# ----------------------------------------------------------------------------- helpers

def pct(values, p):
    if not values:
        return None
    ordered = sorted(values)
    k = max(0, min(len(ordered) - 1, int(round((p / 100.0) * len(ordered) + 0.5)) - 1))
    return ordered[k]


def summarize(latencies_ms, wall_s, errors):
    return {
        "calls": len(latencies_ms),
        "errors": errors,
        "wallSeconds": round(wall_s, 3),
        "callsPerSecond": round(len(latencies_ms) / wall_s, 1) if wall_s else None,
        "p50ms": round(pct(latencies_ms, 50), 2) if latencies_ms else None,
        "p95ms": round(pct(latencies_ms, 95), 2) if latencies_ms else None,
        "p99ms": round(pct(latencies_ms, 99), 2) if latencies_ms else None,
        "maxMs": round(max(latencies_ms), 2) if latencies_ms else None,
        "meanMs": round(statistics.fmean(latencies_ms), 2) if latencies_ms else None,
    }


def proc_stats(pid):
    stats = {"rssKb": None, "threads": None, "fds": None}
    try:
        for line in Path("/proc/%d/status" % pid).read_text().splitlines():
            if line.startswith("VmRSS:"):
                stats["rssKb"] = int(line.split()[1])
            elif line.startswith("Threads:"):
                stats["threads"] = int(line.split()[1])
    except OSError:
        pass
    try:
        stats["fds"] = len(list(Path("/proc/%d/fd" % pid).iterdir()))
    except OSError:
        pass
    return stats


class PeakSampler(threading.Thread):
    """Samples process RSS/thread/fd counts while a load phase runs."""

    def __init__(self, pid, interval=0.05):
        super().__init__(daemon=True)
        self.pid = pid
        self.interval = interval
        self.peak = {"rssKb": 0, "threads": 0, "fds": 0}
        self._halt = threading.Event()

    def run(self):
        while not self._halt.is_set():
            s = proc_stats(self.pid)
            for key in self.peak:
                if s.get(key):
                    self.peak[key] = max(self.peak[key], s[key])
            self._halt.wait(self.interval)

    def stop(self):
        self._halt.set()
        self.join(timeout=2)
        return self.peak


def read_audit(path):
    outcomes = {}
    lines = 0
    if Path(path).exists():
        for line in Path(path).read_text().splitlines():
            if not line.strip():
                continue
            lines += 1
            outcome = json.loads(line)["outcome"]
            outcomes[outcome] = outcomes.get(outcome, 0) + 1
    return {"lines": lines, "outcomes": outcomes}


def machine():
    cpu = "unknown"
    try:
        for line in Path("/proc/cpuinfo").read_text().splitlines():
            if line.startswith("model name"):
                cpu = line.split(":", 1)[1].strip()
                break
    except OSError:
        pass
    try:
        mem_kb = int(next(l.split()[1] for l in Path("/proc/meminfo").read_text().splitlines()
                          if l.startswith("MemTotal:")))
    except (OSError, StopIteration):
        mem_kb = None
    java = subprocess.run(["java", "-version"], capture_output=True, text=True).stderr.splitlines()
    return {
        "cpu": cpu,
        "cores": os.cpu_count(),
        "memoryGb": round(mem_kb / 1024 / 1024, 1) if mem_kb else None,
        "os": platform.platform(),
        "kernel": platform.release(),
        "java": java[0] if java else "unknown",
        "python": sys.version.split()[0],
    }


# ----------------------------------------------------------------------------- scenarios

def scenario_serial(args, work, run):
    audit = work / "audit-serial.log"
    roots = work / "root"
    roots.mkdir(exist_ok=True)
    client = StdioClient(args.jar, base_env(audit, roots))
    try:
        client.initialize()
        for _ in range(args.warmup):
            client.call("tools/call", {"name": "ping", "arguments": {}})
        latencies, errors = [], 0
        start = time.perf_counter()
        for _ in range(args.serial_calls):
            t0 = time.perf_counter()
            response = client.call("tools/call", {"name": "ping", "arguments": {}})
            latencies.append((time.perf_counter() - t0) * 1000)
            if response.get("result", {}).get("isError"):
                errors += 1
        wall = time.perf_counter() - start
        stats = summarize(latencies, wall, errors)
        stats["audit"] = read_audit(audit)
        stats["process"] = proc_stats(client.process.pid)
        return stats
    finally:
        client.close()


def scenario_concurrent(args, work, run):
    audit = work / "audit-concurrent.log"
    roots = work / "root"
    roots.mkdir(exist_ok=True)
    client = StdioClient(args.jar, base_env(audit, roots))
    try:
        client.initialize()
        for _ in range(args.warmup):
            client.call("tools/call", {"name": "ping", "arguments": {}})

        idle = proc_stats(client.process.pid)
        sampler = PeakSampler(client.process.pid)
        sampler.start()
        latencies, errors = [], 0
        lat_lock = threading.Lock()

        def one():
            nonlocal errors
            t0 = time.perf_counter()
            response = client.call("tools/call", {"name": "ping", "arguments": {}})
            elapsed = (time.perf_counter() - t0) * 1000
            with lat_lock:
                latencies.append(elapsed)
                if response.get("result", {}).get("isError"):
                    errors += 1

        start = time.perf_counter()
        with ThreadPoolExecutor(max_workers=args.concurrency) as pool:
            list(pool.map(lambda _: one(), range(args.concurrent_calls)))
        wall = time.perf_counter() - start
        peak = sampler.stop()

        stats = summarize(latencies, wall, errors)
        stats["concurrency"] = args.concurrency
        stats["audit"] = read_audit(audit)
        stats["process"] = {"idle": idle, "peak": peak,
                            "after": proc_stats(client.process.pid)}
        return stats
    finally:
        client.close()


def scenario_backpressure(args, work, run):
    audit = work / "audit-backpressure.log"
    roots = work / "root"
    roots.mkdir(exist_ok=True)
    env = base_env(audit, roots, dict(db_env(args.db_url, args.db_user, args.db_password)))
    env.update({
        "MCP_MAX_CONCURRENT_CALLS": "1",
        "MCP_QUEUE_WAIT_MILLIS": "50",
        "MCP_CALL_TIMEOUT_MILLIS": "30000",
    })
    client = StdioClient(args.jar, env)
    try:
        client.initialize()
        responses = []
        lock = threading.Lock()

        def one():
            response = client.call("tools/call", {
                "name": "run_query",
                "arguments": {"sql": "SELECT pg_sleep(0.2)"},
            })
            with lock:
                responses.append(response)

        start = time.perf_counter()
        with ThreadPoolExecutor(max_workers=args.backpressure_calls) as pool:
            list(pool.map(lambda _: one(), range(args.backpressure_calls)))
        wall = time.perf_counter() - start

        shed = sum(1 for r in responses
                   if "server busy" in json.dumps(r))
        errors = sum(1 for r in responses if r.get("result", {}).get("isError"))
        time.sleep(0.5)  # let the served call unwind and audit
        return {
            "configuredMaxConcurrent": 1,
            "queueWaitMs": 50,
            "offered": args.backpressure_calls,
            "shedBusy": shed,
            "served": len(responses) - shed,
            "errorResults": errors,
            "wallSeconds": round(wall, 3),
            "audit": read_audit(audit),
        }
    finally:
        client.close()


def scenario_cancel(args, work, run):
    audit = work / "audit-cancel.log"
    roots = work / "root"
    roots.mkdir(exist_ok=True)
    env = base_env(audit, roots, dict(db_env(args.db_url, args.db_user, args.db_password)))
    env["MCP_CALL_TIMEOUT_MILLIS"] = "30000"
    client = StdioClient(args.jar, env)
    try:
        client.initialize()
        request_id = client.next_id()
        started = time.perf_counter()
        client._send({"jsonrpc": "2.0", "id": request_id, "method": "tools/call",
                      "params": {"name": "run_query",
                                 "arguments": {"sql": "SELECT pg_sleep(5)"}}})
        time.sleep(0.5)
        cancel_sent = time.perf_counter()
        client.send_notification("notifications/cancelled", {"requestId": request_id})
        response = client.wait(request_id, timeout=15)
        responded = time.perf_counter()
        time.sleep(0.5)
        return {
            "cancelAfterMs": round((cancel_sent - started) * 1000, 1),
            "responseAfterCancelMs": round((responded - cancel_sent) * 1000, 1),
            "totalMs": round((responded - started) * 1000, 1),
            "isError": response.get("result", {}).get("isError"),
            "text": response.get("result", {}).get("content", [{}])[0].get("text"),
            "audit": read_audit(audit),
        }
    finally:
        client.close()


def scenario_timeout(args, work, run):
    audit = work / "audit-timeout.log"
    roots = work / "root"
    roots.mkdir(exist_ok=True)
    env = base_env(audit, roots, dict(db_env(args.db_url, args.db_user, args.db_password)))
    env["MCP_CALL_TIMEOUT_MILLIS"] = "2000"
    client = StdioClient(args.jar, env)
    try:
        client.initialize()
        t0 = time.perf_counter()
        response = client.call("tools/call", {
            "name": "run_query",
            "arguments": {"sql": "SELECT pg_sleep(5)"},
        }, timeout=20)
        elapsed = (time.perf_counter() - t0) * 1000
        time.sleep(0.5)
        return {
            "configuredTimeoutMs": 2000,
            "responseMs": round(elapsed, 1),
            "isError": response.get("result", {}).get("isError"),
            "text": response.get("result", {}).get("content", [{}])[0].get("text"),
            "audit": read_audit(audit),
        }
    finally:
        client.close()


# ----------------------------------------------------------------------------- main

def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--jar", default="target/mcp-dev-tooling-0.1.0.jar")
    parser.add_argument("--out", default="bench/results")
    parser.add_argument("--label", default="")
    parser.add_argument("--warmup", type=int, default=300)
    parser.add_argument("--serial-calls", type=int, default=1000)
    parser.add_argument("--concurrent-calls", type=int, default=5000)
    parser.add_argument("--concurrency", type=int, default=64)
    parser.add_argument("--backpressure-calls", type=int, default=200)
    parser.add_argument("--db-url", default=os.environ.get("BENCH_DB_URL", ""))
    parser.add_argument("--db-user", default=os.environ.get("BENCH_DB_USER", "bench"))
    parser.add_argument("--db-password", default=os.environ.get("BENCH_DB_PASSWORD", "bench"))
    parser.add_argument("--skip-db", action="store_true",
                       help="skip backpressure/cancel/timeout (they need PostgreSQL)")
    args = parser.parse_args()

    jar = Path(args.jar).resolve()
    if not jar.is_file():
        raise SystemExit("jar not found: %s (run: mvn -q package -DskipTests)" % jar)
    if not args.skip_db and not args.db_url:
        raise SystemExit("PostgreSQL required for backpressure/cancel/timeout: "
                         "pass --db-url or --skip-db")

    out_dir = Path(args.out).resolve()
    out_dir.mkdir(parents=True, exist_ok=True)
    stamp = time.strftime("%Y%m%dT%H%M%SZ", time.gmtime())
    work = Path(tempfile.mkdtemp(prefix="mcp-bench-"))

    result = {
        "label": args.label or stamp,
        "timestampUtc": stamp,
        "jar": jar.name,
        "machine": machine(),
        "workload": {
            "warmupCalls": args.warmup,
            "serialCalls": args.serial_calls,
            "concurrentCalls": args.concurrent_calls,
            "concurrency": args.concurrency,
            "backpressureOffered": args.backpressure_calls,
            "rateLimiter": "raised to 1e6 (measuring the funnel, not the limiter)",
        },
        "scenarios": {},
    }

    scenarios = [("serial", scenario_serial), ("concurrent", scenario_concurrent)]
    if not args.skip_db:
        scenarios += [("backpressure", scenario_backpressure),
                      ("cancel", scenario_cancel), ("timeout", scenario_timeout)]

    try:
        for name, fn in scenarios:
            print("running %s ..." % name, file=sys.stderr, flush=True)
            result["scenarios"][name] = fn(args, work, result)
    finally:
        shutil.rmtree(work, ignore_errors=True)

    target = out_dir / ("bench-%s.json" % stamp)
    target.write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps(result, indent=2))
    print("\nwrote %s" % target, file=sys.stderr)


if __name__ == "__main__":
    main()

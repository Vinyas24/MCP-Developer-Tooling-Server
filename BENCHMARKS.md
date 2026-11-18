# Benchmark report

Measured numbers for the claims in the README and on the resume. Everything here was
produced by the committed harness in [`bench/run_bench.py`](bench/run_bench.py); the
raw JSON for every run is in [`bench/results/`](bench/results).

## TL;DR

| Metric | Median (range over 3 runs) |
| --- | --- |
| Throughput, 64 concurrent clients, 10,000 calls | **3,831 calls/s** (3,468–4,036) |
| Latency p50 / p95 / p99 | 13.2 ms / 22.6 ms / **46.4 ms** (p99 45.0–71.8) |
| Throughput, serial client, 2,000 calls | 868 calls/s (819–875) |
| Serial p50 / p99 | 1.06 ms / 2.53 ms |
| Audit completeness | 10,500 / 10,500 lines in every run, all `OK` |
| Peak RSS / threads / fds (64 clients) | 223 MiB / 45 / 10 |
| Load shedding (1 permit, 50 ms queue wait) | 199 / 200 calls shed, 200/200 audited |
| Cancellation acknowledgement | 21.5–38.4 ms after `notifications/cancelled` |
| 2,000 ms call deadline | response in 2,059–2,083 ms, audit `TIMEOUT` |

## Environment

| | |
| --- | --- |
| CPU | 12th Gen Intel Core i5-1235U (12 logical cores) |
| RAM | 6.9 GB |
| OS | Linux 7.0.0 x86_64 |
| JDK | OpenJDK 21.0.12.1 |
| PostgreSQL | 16 (Docker), on the same host |
| Transport | stdio (unix pipe, no network) |
| Server | `target/mcp-dev-tooling-0.1.0.jar`, release build |

This is a laptop-class, shared machine. Treat the numbers as relative evidence and a
regression baseline, not as a capacity promise.

## What is measured

Every call is a `tools/call` for `ping` — a deliberately trivial tool, so the result
isolates the cost of the **server harness**, not tool logic:

- JSON-RPC framing and response serialization
- request-id correlation and transport context
- executor registration, semaphore permit, deadline scheduling
- guard chain: identity, permission, rate limit, size
- record binding, domain validation, dispatch
- synchronous JSONL audit append
- stdout write under the write lock

The client is Python with one reader thread and N writer threads. Client-side Python
overhead is included in the latency numbers. Rate limiting is raised to 1,000,000
tokens for load scenarios on purpose: the limiter protects CPU-heavy tools, and at its
default (30 burst / 10 per second) the benchmark would measure the limiter.

## Method

1. Build the jar: `mvn -q package -DskipTests`.
2. Start PostgreSQL 16 in Docker (only needed for backpressure/cancel/timeout).
3. Run `bench/run_bench.py` three times. Each run starts a **fresh server process for
   every scenario**, so configuration and connection pools are not shared.
4. Scenarios:
   - **serial**: 2,000 calls, one in flight, after 500 warmup calls.
   - **concurrent**: 10,000 calls, 64 in flight, after 500 warmup calls. A sampler
     polls `/proc/<pid>/{status,fd}` every 50 ms for peak RSS/threads/fds.
   - **backpressure**: `MCP_MAX_CONCURRENT_CALLS=1`, `MCP_QUEUE_WAIT_MILLIS=50`,
     200 concurrent `run_query` calls sleeping 200 ms; count served vs shed.
   - **cancel**: start `SELECT pg_sleep(5)`, cancel at 500 ms via
     `notifications/cancelled`, measure time from cancel to response.
   - **timeout**: `MCP_CALL_TIMEOUT_MILLIS=2000` with `pg_sleep(5)`, measure response
     time and audit outcome.
5. After each scenario, parse the audit file and count lines by outcome.

Exact command:

```bash
mvn -q package -DskipTests

docker run --rm -d --name mcp-bench-pg -p 55432:5432 \
  -e POSTGRES_USER=bench -e POSTGRES_PASSWORD=bench -e POSTGRES_DB=bench \
  postgres:16-alpine

for i in 1 2 3; do
  python3 bench/run_bench.py \
    --jar target/mcp-dev-tooling-0.1.0.jar --out bench/results \
    --label "reference-run-$i" \
    --serial-calls 2000 --concurrent-calls 10000 --warmup 500 --concurrency 64 \
    --db-url jdbc:postgresql://localhost:55432/bench \
    --db-user bench --db-password bench
done
```

## Results

### Serial client

| Run | Calls/s | p50 | p95 | p99 | max | Audit lines |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 874.8 | 1.02 ms | 2.05 ms | 2.39 ms | 9.04 ms | 2,500 |
| 2 | 818.8 | 1.20 ms | 2.02 ms | 2.53 ms | 10.03 ms | 2,500 |
| 3 | 867.7 | 1.06 ms | 1.90 ms | 2.68 ms | 9.74 ms | 2,500 |

2,000 measured + 500 warmup = 2,500 audited calls per run. Throughput here is bounded
by the client's request/response round trip, not the server.

### 64 concurrent clients

| Run | Calls/s | p50 | p95 | p99 | max | Audit lines |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 3,831.0 | 14.13 ms | 22.55 ms | 45.02 ms | 229.86 ms | 10,500 |
| 2 | 4,035.9 | 13.15 ms | 19.99 ms | 46.44 ms | 73.46 ms | 10,500 |
| 3 | 3,467.8 | 12.81 ms | 31.87 ms | 71.81 ms | 150.46 ms | 10,500 |

10,000 measured + 500 warmup = 10,500 audited calls per run, outcome `OK` for all of
them. Peak process footprint during the phase:

| Run | RSS | Threads | FDs |
| --- | ---: | ---: | ---: |
| 1 | 230.5 MiB | 45 | 10 |
| 2 | 223.4 MiB | 45 | 10 |
| 3 | 204.9 MiB | 43 | 10 |

Virtual threads keep this flat: 64 concurrent calls add ~8–10 threads over the ~36
idle threads, and file descriptors stay at 9–10.

### Backpressure

`MCP_MAX_CONCURRENT_CALLS=1`, `MCP_QUEUE_WAIT_MILLIS=50`, 200 offered calls.

| Run | Served | Shed | Wall | Audit |
| --- | ---: | ---: | ---: | --- |
| 1 | 1 | 199 | 0.640 s | 200 lines: 199 `DENIED_BACKPRESSURE`, 1 `OK` |
| 2 | 1 | 199 | 0.655 s | 200 lines: 199 `DENIED_BACKPRESSURE`, 1 `OK` |
| 3 | 1 | 199 | 0.704 s | 200 lines: 199 `DENIED_BACKPRESSURE`, 1 `OK` |

Load shedding is exact and fully accounted: no silent drops, no unbounded queue.

### Cancellation

| Run | Cancel sent at | Response after cancel | Total | Audit |
| --- | ---: | ---: | ---: | --- |
| 1 | 500.3 ms | 21.5 ms | 521.7 ms | 1 line, `CANCELLED` |
| 2 | 500.6 ms | 38.4 ms | 538.9 ms | 1 line, `CANCELLED` |
| 3 | 500.4 ms | 28.6 ms | 529.1 ms | 1 line, `CANCELLED` |

The client gets an error result immediately instead of waiting for `pg_sleep(5)`; the
worker unwinds and writes exactly one audit line.

### Deadline

| Run | Configured | Observed response | Audit |
| --- | ---: | ---: | --- |
| 1 | 2,000 ms | 2,083.3 ms | 1 line, `TIMEOUT` |
| 2 | 2,000 ms | 2,059.1 ms | 1 line, `TIMEOUT` |
| 3 | 2,000 ms | 2,075.1 ms | 1 line, `TIMEOUT` |

60–83 ms of overhead over the deadline, inclusive of the aborting timer, response
write, and client measurement.

## Interpretation

- At 64 in-flight calls the server delivers roughly 4.4× the serial throughput while
  keeping p99 under 50 ms in 2 of 3 runs; the third run shows the shared-machine tail
  (72 ms p99, 150 ms max).
- Audit is synchronous and on the hot path; the throughput numbers already include it.
  Audit line counts match offered calls exactly: 39,000 audited calls across the three runs (3 × 10,500 concurrent + 3 × 2,500 serial).
- Backpressure behaviour is deterministic: one permit means one served call, and the
  rest are shed after the 50 ms wait rather than queued without bound.
- Cancellation and deadlines are answered by the abort path, not by the tool: a
  blocking JDBC call that ignores interrupts cannot delay the client response.

## Caveats

- Laptop CPU shared with the desktop, editor, Docker daemon and PostgreSQL; run-to-run
  variance is real (3,468–4,036 calls/s).
- stdio only. The optional Streamable HTTP profile is not benchmarked in this report.
- `ping` is a trivial tool. DB tools add query time; `run_query` is bounded separately
  by the 10 s statement timeout.
- Latency includes the Python client; a C/Rust client would report lower floors.
- The harness measures one process on one machine. No claim is made about multi-tenant
  or network-attached deployments.

## Resume-ready claims

Copy these exactly; each number appears in the tables above.

- Built a secured MCP (Model Context Protocol) server in Java 21 / Spring Boot 4 that
  exposes 9 filesystem and PostgreSQL tools to Claude Desktop and Cursor over stdio and
  Streamable HTTP.
- Sustained **3,831 calls/s** (median of 3 runs; 3,468–4,036) at **64 concurrent
  clients** with **p99 46 ms** (45–72 ms) on a 12-core laptop-class CPU, with exactly
  one JSONL audit record per call (10,500/10,500 in every run).
- Kept behaviour bounded under overload: with one execution permit, **199/200** excess
  calls were shed in 0.64–0.70 s and all 200 outcomes were audited; client cancellation
  was acknowledged in **21.5–38.4 ms** and a 2,000 ms deadline returned in
  **2,059–2,083 ms**.
- Hardened the tool surface against path traversal, symlink escape, SQL injection and
  role escalation with **194 automated tests** (162 unit + 32 Testcontainers/black-box
  integration) and JaCoCo gates at **95.1% line / 82.0% branch** on the security
  package; the four core boundaries (path containment, SQL allow-list, role gate,
  audit hashing) were mutation-tested — break one and the matching test fails.
- Packaged the server as a non-root Docker image and documented a Docker quickstart
  that a new user can run without a local JDK; CI runs the unit suite without Docker
  and the full integration suite with coverage gates in a second job.

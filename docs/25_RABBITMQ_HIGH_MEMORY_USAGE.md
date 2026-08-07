## Current Problem

`docker stats` shows the `chat-app-rabbitmq` container using about **5.28 GiB** of memory, which looks alarming for a dev/staging chat broker:

```text
CONTAINER ID   NAME                MEM USAGE / LIMIT    MEM %   PIDS
3d18070d6c34   chat-app-rabbitmq   5.281GiB / 7.75GiB   68.14%  5032
```

At first glance this looks like a RabbitMQ memory leak or a large queue backlog, but the broker's own internal memory report says otherwise:

```sh
rabbitmq-diagnostics memory_breakdown
```

```text
code: 0.0358 gb
other_system: 0.0265 gb
other_proc: 0.0163 gb
reserved_unallocated: 0.0146 gb
...
queue_procs: 0.0 gb
msg_index: 0.0002 gb
mgmt_db: 0.0003 gb
```

RabbitMQ itself only accounts for about **104 MB**. That means the 5 GB seen by Docker is **not primarily broker heap, queue state, or message index state**.

Further inspection of the container cgroup memory shows the usage is dominated by **anonymous process memory**, not file cache:

```sh
cat /sys/fs/cgroup/memory.stat
```

```text
anon 4795621376
file 569593856
shmem 560095232
```

Finally, the process list inside the container shows the real problem:

```sh
ps aux --sort=-%mem
```

```text
USER       PID %CPU %MEM    VSZ   RSS TTY      STAT START   TIME COMMAND
rabbitmq     1  3.1  1.2 1252056 101748 ?      Ssl  Aug02 150:42 /opt/erlang/lib/erlang/erts-14.2.5.12/bin/beam.smp -W w -MBas ageffcbf -MHas ageffcbf -MBlmbcs 5
root     22576  0.0  0.7 1185296 60316 ?       Sl   Aug03   2:06 /opt/rabbitmq/escript/rabbitmq-diagnostics -B -- -root /opt/erlang/lib/erlang -bindir /opt/erlan
root     19481  0.0  0.7 1184744 57768 ?       Sl   Aug03   0:21 /opt/rabbitmq/escript/rabbitmq-diagnostics -B -- -root /opt/erlang/lib/erlang -bindir /opt/erlan
root     47562  0.0  0.7 1190916 57588 ?       Sl   Aug03   0:19 /opt/rabbitmq/escript/rabbitmq-diagnostics -B -- -root /opt/erlang/lib/erlang -bindir /opt/erlan
root     62354  0.0  0.6 1188172 56400 ?       Sl   Aug04   0:20 /opt/rabbitmq/escript/rabbitmq-diagnostics -B -- -root /opt/erlang/lib/erlang -bindir /opt/erlan
root     11196  0.0  0.6 1183560 56232 ?       Sl   Aug05   0:11 /opt/rabbitmq/escript/rabbitmq-diagnostics -B -- -root /opt/erlang/lib/erlang -bindir /opt/erlan
root     91769  0.0  0.6 1185048 56140 ?       Sl   Aug03   0:22 /opt/rabbitmq/escript/rabbitmq-diagnostics -B -- -root /opt/erlang/lib/erlang -bindir /opt/erlan
root     14349  0.0  0.6 1184760 55984 ?       Sl   Aug03   0:21 /opt/rabbitmq/escript/rabbitmq-diagnostics -B -- -root /opt/erlang/lib/erlang -bindir /opt/erlan
root     70246  0.0  0.6 1184712 55788 ?       Sl   Aug04   0:22 /opt/rabbitmq/escript/rabbitmq-diagnostics -B -- -root /opt/erlang/lib/erlang -bindir /opt/erlan
...
```

There are roughly **150 stuck `rabbitmq-diagnostics` processes**, each consuming around **55 to 60 MB RSS**. The main RabbitMQ BEAM process is only about **100 MB RSS**.

### Root cause

The most likely root cause is the Docker healthcheck in `chat-app-backend/docker-compose.yml`:

```yaml
healthcheck:
  test: ["CMD", "rabbitmq-diagnostics", "ping"]
  interval: 10s
  timeout: 5s
  retries: 5
```

This healthcheck runs every 10 seconds inside the container. In the observed environment, those `rabbitmq-diagnostics ping` invocations are not exiting cleanly, so they accumulate over days and consume several gigabytes of RAM. The 5 GB footprint is therefore **mostly leaked healthcheck subprocess memory**, not normal RabbitMQ broker memory.

### Secondary contributing risks in this app

These do **not** explain the measured 5 GB spike as well as the healthcheck leak does, but they are still operational risks:

- The app uses **durable exchanges** and a **durable per-instance queue** (`ws.{instance-id}.inbound`).
- Messages are published with the default Spring AMQP behavior, which is typically **persistent delivery** unless overridden.
- There is **no queue TTL**, **no max-length**, and **no dead-letter policy** on the instance queue.
- A crashed instance can leave a durable queue on the persisted RabbitMQ volume until that same `instance-id` comes back and deletes it.
- `GroupSummaryUpdatePublisher` still does **O(group_members)** application-level fan-out for sidebar updates.

Those issues matter for backlog growth, broker throughput, and disk usage, but the observed 5 GB memory symptom is best explained by the healthcheck process leak.

### Supporting evidence summary

- `docker stats` shows about **5.28 GiB** and **5032 PIDs** for the RabbitMQ container.
- `rabbitmq-diagnostics memory_breakdown` shows only about **104 MB** attributed to RabbitMQ internals.
- `memory.stat` shows about **4.8 GB anon memory**, not just page cache.
- `ps aux` shows many stuck `rabbitmq-diagnostics` subprocesses at around **56 MB RSS each**.
- The current Docker healthcheck runs that command every **10 seconds**.

## Possible Solutions

### 1. Replace the RabbitMQ healthcheck command

- How it works:
  - Stop using `rabbitmq-diagnostics ping` as the frequent Docker healthcheck.
  - Replace it with a lighter and safer probe, or disable the Docker healthcheck temporarily until a stable probe is chosen.
- Pros:
  - Directly addresses the most likely root cause.
  - Smallest and fastest fix.
  - Does not require app-level topology changes.
- Cons:
  - Requires validating which alternative probe is reliable for this image/environment.
  - Existing leaked processes will still need a container restart to reclaim RAM.
- Recommendation for our problem: **Yes**

Possible replacement candidates to evaluate:

- `rabbitmq-diagnostics -q ping`
- `rabbitmqctl status`
- HTTP health probe against the management API
- Less frequent healthcheck interval if a CLI probe must remain

The best choice should be verified in this environment, because the issue is specifically about how the current CLI probe behaves under Docker here.

### 2. Reduce healthcheck frequency and add operational guardrails

- How it works:
  - Increase the healthcheck interval from 10s to something much less aggressive.
  - Add alerts on container PID count and RabbitMQ container memory.
  - Restart the container automatically or manually when the leak pattern reappears.
- Pros:
  - Reduces the blast radius even before a perfect probe is chosen.
  - Easy operational mitigation.
- Cons:
  - Mitigates symptoms rather than removing the underlying bad probe behavior.
  - Does not protect against all stuck subprocess cases.
- Recommendation for our problem: **Yes**, but only as a mitigation

### 3. Keep RabbitMQ CLI healthchecks, but switch to `rabbitmq-diagnostics -q check_running`

- How it works:
  - Replace `rabbitmq-diagnostics ping` with:
    - `test: ["CMD", "rabbitmq-diagnostics", "-q", "check_running"]`
  - This checks whether the RabbitMQ node is fully booted and running, while keeping a simple direct `CMD` healthcheck.
- Pros:
  - Very simple and readable configuration.
  - Semantically closer to "is RabbitMQ running?" than a raw TCP or HTTP probe.
  - In a short manual test in this environment, it returned success and did not visibly increase the `rabbitmq-diagnostics` process count.
- Cons:
  - It still depends on the same `rabbitmq-diagnostics` CLI family as the leaking `ping` command.
  - A short manual test is not the same as multi-day healthcheck soak testing.
  - If the leak is caused by the CLI/runtime interaction itself, this option may still carry some risk.
- Recommendation for our problem: **Maybe**

### 4. Make the RabbitMQ topology less retention-prone

- How it works:
  - Revisit whether the per-instance inbound queue should remain durable for dev/staging realtime traffic.
  - Add queue policies such as TTL and max-length where message durability is not critical.
  - Periodically clean orphaned `ws.*.inbound` queues when instance identities change.
- Pros:
  - Reduces backlog and stale-object risk.
  - Improves broker hygiene for realtime workloads.
- Cons:
  - Does not explain the measured 5 GB symptom by itself.
  - Needs careful review of delivery guarantees before changing durability.
- Recommendation for our problem: **Yes**, as a secondary hardening task

### 5. Reduce application-level fan-out pressure

- How it works:
  - Keep the current fixed-exchange topology, but continue optimizing `GroupSummaryUpdatePublisher`.
  - Monitor large-group traffic because sidebar updates still publish once per online member.
- Pros:
  - Helps future scale and broker throughput.
  - Complements the existing topology optimizations in `10_RABBITMQ_TOPOLOGY_OPTIMIZATION.md` and `11_GROUP_SUMMARY_UPDATE_FANOUT_SCALING.md`.
- Cons:
  - Not the main fix for this 5 GB incident.
  - More code-level work than changing the healthcheck.
- Recommendation for our problem: **Yes**, but lower priority than fixing the healthcheck

## Recommendation

Recommended path:

1. Keep the currently applied socket+HTTP healthcheck as the chosen fix.
2. Restart the RabbitMQ container to clear the already leaked subprocesses and recover memory.
3. Add basic operational monitoring for RabbitMQ container memory and PID count.
4. After the immediate fix, review whether realtime instance queues and messages need to stay durable in all environments.

### Current chosen fix

- Use:
  - `test: ["CMD-SHELL", "nc -z -w 2 127.0.0.1 5672 && wget -q --spider http://127.0.0.1:15672"]`
- Why this is the chosen fix:
  - It avoids the `rabbitmq-diagnostics` subprocess family entirely.
  - It still validates both the AMQP listener and the management HTTP listener.
  - It is the most conservative option after the observed multi-GB leak.

### Simpler alternative

- Use:
  - `test: ["CMD", "rabbitmq-diagnostics", "-q", "check_running"]`
- Why this was not chosen as the default:
  - It still depends on the same RabbitMQ CLI tool family as the leaking `ping` healthcheck.
  - It looked fine in a short manual test, but it has not been soak-tested long enough in this environment.
  - It remains a reasonable fallback if the team later decides simplicity is more important than being maximally conservative here.

### Final diagnosis

The root cause of the observed **5 GB** memory usage is most likely **leaked Docker healthcheck subprocesses** created by `rabbitmq-diagnostics ping`, not excessive RabbitMQ broker memory, queue backlog, or message payload size.

## Implementation details

### Phase 1 - Investigation completed

- What changed:
  - Collected container-level memory evidence (`docker stats`, `memory.stat`, process list).
  - Compared Docker memory with RabbitMQ internal memory accounting.
  - Reviewed the current app topology and delivery model to separate primary cause from secondary risks.
- Why it changed:
  - We needed to distinguish between a real RabbitMQ broker memory issue and a container/process-level issue.
- Rollout, migration, or backward-compatibility notes:
  - No code or infrastructure change has been applied yet in this phase.

### Phase 2 - Docker healthcheck fix applied

- What changed:
  - Replaced the RabbitMQ Docker healthcheck command from `rabbitmq-diagnostics ping` to a lightweight socket+HTTP probe:
    - `nc -z -w 2 127.0.0.1 5672`
    - `wget -q --spider http://127.0.0.1:15672`
  - Increased the healthcheck interval from `10s` to `30s`.
  - Added `start_period: 20s` to reduce startup flapping.
- Why it changed:
  - The old healthcheck was the most likely source of leaked `rabbitmq-diagnostics` subprocesses and multi-GB anonymous memory growth.
  - The new probe avoids spawning Erlang CLI diagnostics on every healthcheck run while still verifying both the AMQP port and management UI listener.
- How the new command works:
  - `nc -z -w 2 127.0.0.1 5672`
    - `nc` (netcat) opens a lightweight TCP check.
    - `-z` means "scan only" or "do not send data"; it only checks whether the port is accepting connections.
    - `-w 2` sets a 2-second timeout.
    - `127.0.0.1 5672` targets the RabbitMQ AMQP listener inside the same container.
    - In short: this confirms the broker is accepting AMQP TCP connections.
  - `wget -q --spider http://127.0.0.1:15672`
    - `wget` performs a lightweight HTTP request.
    - `-q` suppresses noisy output.
    - `--spider` checks the URL without downloading a response body to a file.
    - `http://127.0.0.1:15672` targets the RabbitMQ management UI listener inside the same container.
    - In short: this confirms the HTTP management endpoint is up.
  - `&&`
    - The second check only runs if the first one succeeds.
    - The whole healthcheck fails if either the AMQP port or the management HTTP listener is unavailable.
  - Combined effect:
    - The healthcheck verifies both network listeners we care about, while avoiding the `rabbitmq-diagnostics` subprocess family entirely.
- Rollout, migration, or backward-compatibility notes:
  - Existing leaked processes are not removed by the compose-file change alone; the RabbitMQ container must be recreated or restarted to reclaim RAM.
  - Recreating a RabbitMQ container on a persisted bind mount can expose a separate startup issue if the Erlang node name changes between runs.

### Phase 3 - Stabilize RabbitMQ node name across container recreation

- What changed:
  - Added `hostname: rabbitmq` to the RabbitMQ service in Compose.
  - Added `RABBITMQ_NODENAME: rabbit@rabbitmq` to keep the persisted Mnesia node name stable.
- Why it changed:
  - The bind-mounted RabbitMQ data directory already contained state for old node names such as `rabbit@3d18070d6c34`.
  - After recreating the container, Docker assigned a new hostname such as `f0f9702f4721`, which changed the RabbitMQ Erlang node name to `rabbit@f0f9702f4721`.
  - That node-name mismatch can prevent RabbitMQ from finishing boot on reused persisted state.
- Rollout, migration, or backward-compatibility notes:
  - This prevents future random node-name drift when the container is recreated.
  - Existing persisted state may still need one-time cleanup in dev/staging if it already contains broken or stale node-specific Mnesia data from old container hostnames.

## Lesson (look back here)

- High container memory does not automatically mean high RabbitMQ broker memory.
- Always compare `docker stats` with `rabbitmq-diagnostics memory_breakdown` before assuming queue or heap growth.
- If RabbitMQ internal memory is small but container RSS is huge, inspect cgroup memory and process lists next.
- PID count is an important clue: thousands of processes inside a RabbitMQ container usually point to a sidecar/tooling/healthcheck problem, not normal broker behavior.

## Future Higher-Scale Path

- If RabbitMQ remains in the architecture, continue reducing unnecessary persistence for ephemeral realtime events where safe.
- Periodically review orphan queue cleanup on the persisted RabbitMQ volume.
- Keep watching the per-member sidebar fan-out path for large active groups.
- If the system fully migrates to Redis pub/sub for cross-instance realtime delivery, remove RabbitMQ entirely and drop this operational class of problems.

# Media Processing Job Deduplication State Race

## Current Problem

`InMemoryMediaProcessingJobDeduplicationStore` originally tracked job lifecycle in **two separate sets**:

- `completedJobIds`
- `inProgressJobIds`

Claim, complete, and release were separate operations on different structures. That is a **split-state, check-then-act** race: threads can observe an impossible or stale combination of the two sets.

Related code: `media-processing/src/main/java/com/hello/mediaprocessing/service/InMemoryMediaProcessingJobDeduplicationStore.java`, called from `MediaProcessingJobHandler`.

## What kind of race is this?

| Name                                | How it maps here                                                                                                                                                 |
| ----------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Split-state TOCTOU**              | `tryBeginProcessing` checked `completedJobIds`, then mutated `inProgressJobIds` as a second step. Another thread could complete or release between those steps.  |
| **Non-atomic lifecycle transition** | `markCompleted` removed the job from `inProgressJobIds` before adding it to `completedJobIds`. For a short window the job was neither in-progress nor completed. |
| **False-negative idempotency**      | A duplicate delivery could be accepted **after** the worker had already finished, because a concurrent claim slipped through during the torn transition.         |

This is **in-process** concurrency (one JVM). `ConcurrentHashMap.newKeySet()` makes each individual set thread-safe, but **two sets are not one atomic state machine**.

Severity is lower than cross-instance DB races (see [race_condition_issues.md](./race_condition_issues.md)), but it breaks the worker guarantee that a `MEDIA_READY` job id stays deduplicated under concurrent RabbitMQ redelivery on the same pod.

## Examples (status quo — before the fix)

### 1. Late claim after completion (torn `markCompleted`)

```text
Thread A (finishing worker):  inProgress.remove(jobId)
Thread B (duplicate delivery):  completed.contains(jobId) → false
Thread B:                       inProgress.add(jobId) → true   // claim succeeds
Thread A:                       completed.add(jobId)
```

Thread B starts processing a job that should have been skipped as a duplicate.

### 2. Check-then-act in `tryBeginProcessing`

```text
Thread A:  completed.contains(jobId) → false
Thread B:  markCompleted(jobId)
Thread A:  inProgress.add(jobId) → true
```

Same outcome: duplicate work after terminal success.

### 3. Intended retries must still work

Deferred, failed, and partial-progress outcomes call `releaseProcessing` so the same `jobId` can be retried. Completion must remain sticky; release must not erase a completed job. The fix must preserve both behaviors.

## Possible Solutions

### 1. Single per-job state in `ConcurrentHashMap` + atomic `compute` (chosen)

- How it works: one map entry per `jobId` with states `IN_PROGRESS` and `COMPLETED`. `tryBeginProcessing`, `markCompleted`, and `releaseProcessing` use `compute` / `computeIfPresent` so each transition is atomic for that key.
- Pros: Minimal code; matches a small state machine; easy to test with a barrier-based concurrency test.
- Cons: Still process-local; does not coordinate across `media-processing` pods.
- Recommendation: **Yes** for the current in-memory store.

### 2. Keep two sets but wrap every method in `synchronized(jobId.intern())`

- Pros: Easy to read.
- Cons: String intern pitfalls; still not durable; coarse if extended carelessly.
- Recommendation: **No**.

### 3. Distributed idempotency store (Redis / DB)

- Pros: Correct across instances; survives restarts.
- Cons: Extra infra and fencing/lease rules; out of scope for Phase 1–4 worker.
- Recommendation: **Later** — see TODO in `InMemoryMediaProcessingJobDeduplicationStore`.

## Recommendation

Use one `ConcurrentHashMap<String, JobState>` and atomic remapping functions:

- `tryBeginProcessing`: absent → `IN_PROGRESS`; reject `IN_PROGRESS` or `COMPLETED`
- `markCompleted`: always → `COMPLETED`
- `releaseProcessing`: `IN_PROGRESS` → remove entry; leave `COMPLETED` unchanged

## Implementation details

### What changed

- Replaced dual `Set` tracking with `ConcurrentHashMap<String, JobState>`.
- `tryBeginProcessing` uses `compute` to claim only when the current state is absent.
- `markCompleted` uses `compute` to set `COMPLETED` atomically.
- `releaseProcessing` uses `computeIfPresent` to clear only `IN_PROGRESS` claims.
- `MediaProcessingJobHandler` already:
  - defers before claiming when no targets are enabled
  - marks complete only on `MEDIA_READY`
  - releases the claim on retryable failures and partial progress
- Added `InMemoryMediaProcessingJobDeduplicationStoreTest.concurrentCompletionAndClaims_preventsLateClaims` (barrier-aligned `markCompleted` vs many `tryBeginProcessing` calls).

### Why it changed

`ConcurrentHashMap` collections alone do not compose into an atomic multi-step lifecycle. Per-key `compute` provides the same mutex the two-set design was missing.

### Rollout notes

- Behavior change is limited to `media-processing` worker deduplication on a single instance.
- The distributed/durable coordination TODO remains until multi-instance rollout.

## Examples (after the fix)

```text
Thread A:  compute(jobId) → COMPLETED
Thread B:  compute(jobId) sees COMPLETED → claim rejected
```

```text
Thread A:  releaseProcessing(jobId) removes IN_PROGRESS entry
Thread B:  tryBeginProcessing(jobId) → claim succeeds (retry allowed)
```

## Lesson (look back here)

When a feature is modeled as a **state machine** (absent → in-progress → completed, with release back to absent), store **one authoritative state per key** and transition with a single atomic operation (`compute`, CAS, or DB `UPDATE … WHERE status = ?`). Multiple parallel flags or sets recreate TOCTOU even inside `ConcurrentHashMap`.

Same family as “shared mutable in-memory maps” and “check-then-act” in `.cursor/rules/avoid-race-conditions.instructions.mdc` and [22_DYNAMIC_RABBITMQ_LISTENER_REGISTRY_CONCURRENCY.md](./22_DYNAMIC_RABBITMQ_LISTENER_REGISTRY_CONCURRENCY.md).

## Future Higher-Scale Path

Replace the in-memory map with a durable idempotency store (DB row or Redis with TTL) before running multiple `media-processing` replicas, so completion survives process restarts and is visible across pods. Until then, treat this store as **best-effort per instance** only.

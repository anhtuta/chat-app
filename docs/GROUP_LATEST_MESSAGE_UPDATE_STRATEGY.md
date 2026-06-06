# Group Latest Message Update Strategy

## Current Problem

When many users send messages to the same group at the same time, the previous implementation used a pessimistic lock on the group row before updating `latest_message` fields.

This causes two main issues for hot groups:

- Throughput bottleneck: writes are serialized behind one lock.
- Higher latency: each request can wait for earlier transactions holding the lock.

## Possible Solutions

### 1. Pessimistic lock per message write (previous approach)

How it works:

- Lock group row (`PESSIMISTIC_WRITE`)
- Save message
- Update group latest fields

Pros:

- Simple and strongly consistent

Cons:

- Poor scalability on hot groups
- Lock wait/queue under high concurrency

Recommendation for our problem:

- Not recommended for high-concurrency groups

### 2. Compare-and-set (CAS) update without explicit lock (chosen)

How it works:

- Save message first
- Update group latest fields only if incoming message is newer than current latest
- If update affects `0` rows, ignore (another equal/newer message already won)

Pros:

- No pessimistic lock contention
- Better throughput for concurrent writes
- Still prevents stale overwrite of latest summary

Cons:

- Slightly more complex update condition

Recommendation for our problem:

- Recommended and implemented

### 3. Optimistic locking with retry

How it works:

- Add version checks and retry on conflict

Pros:

- Avoids database row lock waiting

Cons:

- Retry loops add extra write pressure and complexity
- For latest-summary use case, retry is usually unnecessary

Recommendation for our problem:

- Not recommended as primary approach for this flow

### 4. Async projection (event-driven, eventually consistent)

How it works:

- Request path saves message
- Background consumer updates group latest fields

Pros:

- Highest write scalability
- Decouples message write from summary update workload

Cons:

- Eventually consistent latest fields (small lag)
- More infrastructure and operational complexity

Recommendation for our problem:

- Good future option when scale grows significantly

### 5. Remove denormalized latest fields from group table

How it works:

- Compute latest from messages table on read path

Pros:

- Simplifies write path

Cons:

- Slower/more expensive reads
- Does not match product requirement to keep latest fields in group row

Recommendation for our problem:

- Not applicable (we must keep group latest fields updated)
- (Do t muốn bắt buộc phải có cột `latest_message` trong bảng group, thì mới có bài toán thú vị để giải quyết chứ! Nếu bỏ nó đi thì còn gì hay nữa! Nên cách này ko đc chọn!)

## Recommendation

1. Near-term: replace pessimistic lock with synchronous compare-and-set update in DB.
2. Mid-term (if traffic grows): move latest-field update to async consumer with per-group ordering.
3. Keep denormalized latest fields in group table in both approaches.

## Chosen Solution

We use **compare-and-set (CAS) update without explicit lock**.

Implementation summary:

- `MessageService.saveGroupMessage(...)` saves the message.
- `GroupRepository.updateLatestMessageIfNewer(...)` performs a conditional update:
  - update only when `latest_message_at` is null
  - or incoming timestamp is newer
  - or same timestamp but incoming message id is larger (tie-break)
- If no row is updated, we intentionally ignore it because a newer/equal latest is already present.

This keeps `groups.latest_message*` fields accurate without serializing writers on a pessimistic row lock.

Notes

- CAS condition currently uses timestamp and message-id tie-break logic to avoid stale overwrite under concurrency.
- This gives better hot-group write concurrency than row-level pessimistic locking while still keeping group latest fields updated.

Why this exists:

- Two messages can share the same timestamp (same second or same micro precision window).
- Timestamp alone cannot decide which one is latest.
- Message id provides deterministic ordering inside that timestamp.

## Future Higher-Scale Path

If group write traffic grows beyond what synchronous CAS can handle comfortably, the next step is:

- Move latest-summary update to async projection (event/queue consumer)
- Keep same CAS/idempotent rule in consumer
- Partition by `groupId` to preserve per-group ordering

That provides higher throughput while keeping safe convergence of latest summary fields.

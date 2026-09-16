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

## `refreshGroupLatestMessage` (edit / delete path)

CAS (`updateLatestMessageIfNewer`) only runs on **new** message inserts: it advances the group summary when the incoming row is newer. It does **not** cover moderation, where the chronologically latest row can stay the same while its preview must change.

### When we use it

`MessageModerationService` calls `MessageService.refreshGroupLatestMessage(groupId, moderatedMessageId)` after a successful:

- `PATCH /api/messages/{messageId}` (text edit)
- `DELETE /api/messages/{messageId}` (soft delete)

for **group** messages only (public chat has no group summary columns).

### What it does

1. Verify the group exists.
2. Query the latest message: `findTopByGroup_IdOrderByTimestampDescIdDesc`.
3. If none: `clearLatestMessageIfEmpty` (conditional clear so a concurrent send is not wiped).
4. **Early exit** if `moderatedMessageId != latest.id` — an older message’s edit/delete cannot change the sidebar preview. Soft-delete of the latest row still matches (same id, preview becomes `"Message deleted"`).
5. Otherwise: write preview/sender/timestamp via `updateLatestMessageIfNotStale` (CAS with `<=` on message-id tie-break).

### Concurrency note

A plain `group.setLatestMessage(...); save(group)` is unsafe: a concurrent `saveGroupMessage` CAS can land between the `findTop` read and the save, and the refresh would overwrite the newer summary.

Do **not** reuse `updateLatestMessageIfNewer` for this path. Its tie-break uses `<`, so editing/deleting the current latest message (same timestamp + same id, new preview) would update **0** rows. `updateLatestMessageIfNotStale` uses `<=`, which:

- still refuses to overwrite a **newer** concurrent summary
- allows rewriting the preview when the candidate **is** the current latest (edit / soft-delete)

### Relationship to the FE sidebar

| Layer                              | Behavior today                                                                                                     |
| ---------------------------------- | ------------------------------------------------------------------------------------------------------------------ |
| **DB** (`groups.latest_message*`)  | Updated by `refreshGroupLatestMessage` so the next `GET /api/groups` / reload shows the correct preview.           |
| **Acting client sidebar**          | Patched locally from the edit/delete HTTP response when the moderated message is the current latest (Phase 11 UI). |
| **Other online clients’ sidebars** | **Not** updated by this method. No `GroupSummaryUpdate` WebSocket fan-out yet; that is Feature 15 **Task 12.4**.   |

So: yes, edit/delete use this method so the **persisted** sidebar preview stays coherent — but it is a DB recompute helper, not the realtime sidebar publisher.

## Future Higher-Scale Path

If group write traffic grows beyond what synchronous CAS can handle comfortably, the next step is:

- Move latest-summary update to async projection (event/queue consumer)
- Keep same CAS/idempotent rule in consumer
- Partition by `groupId` to preserve per-group ordering

That provides higher throughput while keeping safe convergence of latest summary fields.

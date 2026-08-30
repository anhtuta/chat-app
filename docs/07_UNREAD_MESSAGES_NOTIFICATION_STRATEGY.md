# Unread Messages And Notification Strategy

## Current Problem

The app already updates each group row in real time (latest message preview and timestamp), but it does not persist read state per user per group.

Because of that:

- If a user is offline, new group messages are not tracked as unread.
- If a user is online but not currently inside a group, new messages in that group are not persisted as unread for that user.
- Sidebar previews can be fresh while unread badges are missing or inconsistent.

We need a durable unread model that works for both offline and online-not-in-group users.

### About “offline” vs “online but not in group”

This is the key product decision.

There are really two separate concepts:

1. Presence: is the user connected at all?
2. Read context: is the user actively viewing this group?

Unread should be based on **read context**, not only presence.

Why:

1. A user can be online and subscribed to sidebar updates, but still not have read the new group messages.
2. A user can even be subscribed to the group topic and still not have actually opened the conversation window in a meaningful read state.
3. “Connected” is not the same as “has seen”.

So the rule should be: **A message is unread for a participant until the client explicitly marks that group as read**.

That covers both offline users and online users who are not in that group, with one consistent model.

## Possible Solutions

### 1. Inbox table per user-message pair

How it works:

- On each group message, insert one inbox row per recipient.
- Frontend reads inbox rows to show notifications.
- Rows are deleted or marked consumed after reading.

Pros:

- Explicit notification records.
- Easy to build notification history later.

Cons:

- Write amplification (fan-out rows per message).
- Extra lifecycle complexity (insert, cleanup, dedupe, retries).
- Duplicates delivery state separate from message source of truth.

Recommendation for our problem:

- Not recommended for current scope.

When I’d use it:

- Only if you need persistent notification history as a separate product feature, not just unread counts.

#### Details: With an inbox table

- You duplicate data or at least duplicate message-user delivery records.
- You need lifecycle management: insert on send, delete on read, handle reconnects, handle partial reads, handle message edits/deletes later.
- For large groups, one message fan-outs into many DB writes anyway, so you do not actually avoid write amplification.
- It becomes a delivery log, not a read-state model. Those are different concerns.

### 2. Read cursor on GroupParticipant (chosen)

How it works:

- Persist `last_read_message_id` on each membership row (`group_participants`).
- Unread for a user/group = count of messages where `message.id > last_read_message_id`.
- Mark read by updating the cursor when user opens a group and latest message is loaded.

Pros:

- No duplicated message storage.
- One canonical message source of truth.
- Simple mark-read semantics (single row update per group/user).
- Handles both offline and online-not-in-group states.

Cons:

- Unread counts are derived via queries (not precomputed cache).
- Can become query-heavy at very large scale without caching/materialization.

Recommendation for our problem:

- Recommended and implemented.

#### Details: With a read cursor

With a read cursor on `GroupParticipant`:

1. Message stays canonical in one table.
2. Read/unread becomes derived state: unread means `message.id > lastReadMessageId` for that user and group.
3. Mark-as-read is one update to the membership row, not deletes from another table.
4. Total unread count can be computed with count queries or maintained as a cached counter later if needed.

#### `last_read_at` vs `last_read_message_id`

I would prefer `lastReadMessageId` over `lastReadAt`.

Pros of `lastReadMessageId`:

1. It is deterministic even when multiple messages have the same timestamp.
2. It matches your cursor pagination style already, where messages are ordered by timestamp plus id.
3. It avoids edge cases from clock precision or server time differences.
4. Counting unread is straightforward with a query like “count messages in this group with id > lastReadMessageId”.

Cons of `lastReadMessageId`:

1. It assumes message ids are monotonic and usable as ordering within a group. In your current app that is true enough for a single DB sequence.
2. If you later shard messages heavily, pure id-based semantics may become less portable.

Pros of `lastReadAt`:

1. Easy to understand.
2. Convenient for UI like “last seen at”.

Cons of `lastReadAt`:

1. Timestamp equality edge cases are annoying.
2. It is weaker than message id for exact cursor semantics.
3. Your app already uses message id in pagination, so using time alone would be less consistent.

Best practical choice:

- Store `lastReadMessageId`, and if you want, also store `lastReadAt` as denormalized metadata.
- If forced to choose one, choose `lastReadMessageId`.

### 3. Precomputed unread counters only

How it works:

- Store mutable unread counters per user/group and update on send/read.

Pros:

- Fast reads.

Cons:

- More write complexity.
- Harder consistency guarantees during retries/failures.

Recommendation for our problem:

- Better as a later optimization layer, not the base model.

### 4. Hybrid: `lastReadMessageId` plus cached `unreadCount` on `GroupParticipant`

How it works:

- Store `lastReadMessageId` for correctness.
- Also maintain a cached `unreadCount` that is incremented/decremented on message send and read.

Pros:

- Very fast UI reads.
- Total unread count becomes trivial.

Cons:

- More write-side complexity.
- Must update counts transactionally on every message send and read.
- Easier to get out of sync.

When I’d use it:
Only after the pure cursor design proves too slow at your scale.

### 5. Separate notification event table without copying full message content

How it works:

- On each message, insert a lightweight notification event per recipient (user_id, group_id, message_id, created_at).
- Frontend queries this table for unread counts and notifications.
- Mark read by deleting or marking consumed the relevant notification events.
- Example: user_id, group_id, message_id, created_at, read_at

Pros:

- No full content duplication.
- Good audit trail for notification history.

Cons:

- Still fan-out rows per recipient.
- Still more lifecycle complexity than a cursor.
- Overkill if all you need is unread badges.

When I’d use it:

- If you explicitly want notification center/history, push retries, or per-message acknowledgement.

## Recommendation

Start with

1. Add `lastReadMessageId` to `GroupParticipant`
2. Mark read when the user opens the group and the frontend has loaded/rendered the latest messages for that group
3. Compute unread count as messages newer than `lastReadMessageId`
4. Return `unreadCount` per group in the groups API
5. Derive `totalUnreadCount` on the client as the sum of per-group unread counts (no dedicated total endpoint)
6. Keep your existing real-time `GroupSummaryUpdate` event, and use it to refresh badges/sidebar instantly

This is the cleanest model for your current architecture.

### Why this matches your codebase specifically

Your backend already has:

1. `GroupParticipant` as the exact per-user-per-group row where read state belongs.
2. `Group.latestMessageAt` and `latestMessage` metadata for cheap sidebar refresh.
3. `Group` message retrieval by cursor, so a message-id-based read marker is aligned with existing pagination behavior.
4. User-specific group summary updates over WebSocket, which can trigger badge refreshes without polling.

### What I would not do

I would not condition unread creation on “offline users only” inside sendGroupMessage.

That sounds attractive, but it is the wrong abstraction because:

1. WebSocket connection state is not equal to read state.
2. Online-but-not-reading users would be mishandled.
3. Multi-device cases get messy fast.
4. Reconnect timing and transient disconnects create edge cases.

Unread should be cleared only by an explicit read action, not by connection status.

### Main tradeoff to watch

The main downside of the cursor approach is counting unread efficiently.

At moderate scale, this is fine with indexes and targeted queries. If later it becomes hot, you can evolve to:

1. cached unread counters on GroupParticipant, or
2. async materialization for badge counts

That is a good second step, not the first one.

## Chosen Solution

We use a **read cursor on membership (`last_read_message_id`)** and derive unread counts from messages newer than that cursor.

Implementation summary:

### Backend

- Schema:
  - Added `group_participants.last_read_message_id` via `V6__add_last_read_message_id_to_group_participants.sql`.
- Domain:
  - Added `lastReadMessageId` to `GroupParticipant` entity.
- Query logic:
  - Added user-scoped bulk unread query in `MessageRepository`:
    - `findUnreadCountRowsByUserId(userId)`.
  - `GroupService` maps these rows into `groupId -> unreadCount` in one pass to avoid N+1 counting queries.
- APIs:
  - `GET /api/groups` now returns `unreadCount` per group in `GroupResponse`.
  - `POST /api/groups/{groupId}/read` marks a group as read up to `lastReadMessageId`.
  - Total unread is derived on the client from per-group `unreadCount` values (no dedicated total endpoint).
- Validation:
  - Mark-read endpoint validates membership and validates that `lastReadMessageId` belongs to the same group.

### Frontend

- Data flow:
  - `ChatPage` now consumes `unreadCount` from `GET /api/groups`.
  - When user opens a group and latest visible message is available, frontend calls:
    - `POST /api/groups/{groupId}/read` with `lastReadMessageId`.
  - After successful mark-read, that group badge is reset to zero locally.
- Real-time behavior:
  - Existing personal summary stream (`/topic/user.<username>.group-updates`) is kept.
  - On incoming summary update, sidebar preview fields still update instantly.
  - If user is not currently in that group, `unreadCount` increments instantly for that row.
- UI:
  - Sidebar now shows per-group unread badge and total unread count.

### Why it changed

- To persist unread state correctly across offline periods and across groups the user is not actively viewing.

### API/contract/config impacts

- `GroupResponse` now includes `unreadCount`.
- New endpoint: `POST /api/groups/{groupId}/read`.
- New DB column: `group_participants.last_read_message_id`.
- Removed unused `GET /api/groups/unread/total`; the sidebar totals unread from per-group counts.

### Rollout/migration/backward-compatibility notes

- Existing clients can continue consuming `GET /api/groups` (new field is additive).
- Applying migration V6 is required before deploying backend code that validates schema.
- Existing participants start with `last_read_message_id = null`, so current unread is computed as all historical messages in each joined group until read cursor is set.

## Future Higher-Scale Path

If unread queries become hot at higher scale:

- Add async materialization/caching of per-user unread totals.
- Maintain denormalized unread counters with transactional safeguards.
- Batch/debounce unread refreshes for bursty group traffic.
- Add read-receipt events for multi-device synchronization if needed.

# Unread Messages And Notification Strategy

## Current Problem

The app already updates each group row in real time (latest message preview and timestamp), but it does not persist read state per user per group.

Because of that:

- If a user is offline, new group messages are not tracked as unread.
- If a user is online but not currently inside a group, new messages in that group are not persisted as unread for that user.
- Sidebar previews can be fresh while unread badges are missing or inconsistent.

We need a durable unread model that works for both offline and online-not-in-group users.

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
  - `GET /api/groups/unread/total` returns aggregated unread count.
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
- New endpoint: `GET /api/groups/unread/total`.
- New DB column: `group_participants.last_read_message_id`.

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

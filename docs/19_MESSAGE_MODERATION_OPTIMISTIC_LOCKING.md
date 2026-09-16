# Message Moderation Optimistic Locking

Severity: 🟠 Major

## Current Problem

`MessageModerationService.editMessage` / `deleteMessage` load a message with `findWithMediaById` (no lock) and then `save` it. `Message` has no `@Version` field.

Under concurrent requests on the same message:

| Race          | What can go wrong                                                                                                                                       |
| ------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Two edits     | Last write wins on `content`. Both `message_edit_history` rows may store the same stale `oldContent`, so the audit trail skips an intermediate version. |
| Edit + delete | Dirty-field updates usually leave the row soft-deleted, sometimes with updated content. Behavior is order-dependent and hard to reason about.           |
| Two deletes   | Mostly harmless (`deleted_at` / `deleted_by` last write wins).                                                                                          |

This is uncommon in chat (one author, occasional moderator), but it is a real correctness gap for edit history and conflict handling.

Related code:

- `Message` (`updated_at` is a **business** “last content edit” timestamp, not a concurrency token)
- `MessageModerationService.editMessage` / `deleteMessage` (`findWithMediaById` then `save`; edit also inserts `message_edit_history`)
- Feature 15 message moderation APIs (`PATCH` / `DELETE /api/messages/{messageId}`)

## Examples (status quo — not fixed)

This is **same-row** last-write-wins, not membership TOCTOU (doc 23). It can happen in **group or public** chat: two devices of the author, or author + `CO_LEADER` (`EDIT_ANY_TEXT_MESSAGE` / `DELETE_ANY_MESSAGE`).

Both TXs: load message (no lock, no `@Version`) → auth → mutate in memory → `save`. Hibernate flushes whatever fields that persistence context thinks are dirty. There is no `UPDATE … WHERE version = ?`.

### 1. Two concurrent edits — skipped history version

The message content is `"Hello"`. Alice (author) and Bob (`CO_LEADER`) both edit it.

1. Both `PATCH /api/messages/{id}` load the row with `content = "Hello"`.
2. Alice’s TX inserts history `oldContent = "Hello"` and sets content to `"Hi"`.
3. Bob’s TX also inserts history `oldContent = "Hello"` (still the snapshot he loaded) and sets content to `"Hey"`.
4. Both commit. Last writer wins on `messages.content` (e.g. `"Hey"`).

**Broken outcome:** Two history rows both claim the previous text was `"Hello"`. If Alice committed first, the audit trail never records `"Hi"` as an intermediate version. Clients that already showed `"Hi"` can be overwritten by `"Hey"` with no 409.

### 2. Edit and delete at the same time

Alice starts an edit; Bob starts a delete of the same text message.

1. Both load the row with `deleted_at = null` and current content.
2. Alice’s TX: `deleted_at` check passes; history insert; `content` / `updated_at` / `updated_by` set.
3. Bob’s TX: `deleted_at` check passes; `deleted_at` / `deleted_by` set.
4. Both `save` and commit. Order-dependent:
   - Delete commits last → row is soft-deleted, possibly also with Alice’s new content (dirty-field updates can both apply).
   - Edit commits last → if the edit session still has `deleted_at = null`, a full-state flush can **clear** Bob’s delete (message looks edited and alive). Dirty-field-only flush may keep the delete and still change content.

**Broken outcome:** Soft-deleted message with a new edit, or a delete that disappears. Hard to explain in the API.

### 3. Two concurrent deletes

Alice and Bob both `DELETE /api/messages/{id}`.

1. Both load `deleted_at = null` and pass “not already deleted.”
2. Both set `deleted_at` / `deleted_by` and save.

**Broken outcome:** Mostly harmless — the row stays soft-deleted. `deleted_by` is last write wins (may show Bob even if Alice deleted first). No duplicate “already deleted” error for the loser.

These are lost updates on `messages`: **time of check** = unlocked load + in-memory `deleted_at` / `content`; **time of use** = `save` with no version/CAS. Doc 23 is different: membership on another table can change without touching this row.

**Related but different race:** concurrent kick/ban/role change vs edit/delete (auth TOCTOU on `group_participants`) — see [23_MESSAGE_MODERATION_MEMBERSHIP_AUTH_RACE.md](./23_MESSAGE_MODERATION_MEMBERSHIP_AUTH_RACE.md). Message `@Version` does not cover that case.

## Possible Solutions

### 1. Dedicated `@Version` column (optimistic locking)

- How it works: Add `messages.version INTEGER NOT NULL DEFAULT 0` and `@Version` on `Message`. Hibernate includes `version` in `UPDATE … WHERE id=? AND version=?` and increments on successful flush. Map `OptimisticLockException` / `ObjectOptimisticLockingFailureException` to HTTP 409; client refreshes and retries.
- Pros: Standard JPA approach; protects edit and delete; keeps edit-history chain consistent under conflict (loser fails instead of writing stale `oldContent`); no row lock held for the whole TX.
- Cons: Requires a migration and API/error-contract handling; FE must handle 409.
- Recommendation for our problem: **Yes** (preferred when we fix this).

### 2. Reuse `updated_at` as `@Version`

- How it works: Annotate existing `updatedAt` with `@Version` (Hibernate allows temporal versions).
- Pros: No new column.
- Cons: Conflates business “last content edit” with concurrency control; soft-delete today does not bump `updated_at`; Hibernate would own the field on every flush (including delete); null until first edit; time precision races. **Do not use.**
- Recommendation for our problem: **No**.

### 3. Pessimistic lock on moderation load

- How it works: `SELECT … FOR UPDATE` (e.g. `@Lock(PESSIMISTIC_WRITE)`) when loading the message inside edit/delete.
- Pros: Serializes mutations on that row; no version column; simple mental model.
- Cons: Holds a DB row lock for the TX; does not help if other code paths update `Message` without the same lock; less ideal if moderation TXs grow (history write, summary refresh).
- Recommendation for our problem: **No** as primary approach. Acceptable fallback if we must avoid a schema change short-term.
- When I’d use it: Very short critical sections, or one-off scripts—not the long-term moderation design.

### 4. Conditional SQL without a version column

- How it works: e.g. edit/delete `UPDATE … WHERE id=? AND deleted_at IS NULL` (optionally also `content = :expectedOldContent` for edits). Treat `0` rows updated as conflict.
- Pros: Small change; blocks editing an already-deleted message and ambiguous double-delete.
- Cons: Without content/version CAS, two concurrent edits can still last-write-wins and duplicate stale `oldContent` in history.
- Recommendation for our problem: **No** as the full fix. Possible interim hardening only.
- When I’d use it: Tiny patch before a proper `@Version` migration.

### 5. Accept last-write-wins (status quo)

- How it works: Document that concurrent moderation is last-write-wins; no schema/API change.
- Pros: Zero cost.
- Cons: Incorrect edit-history under concurrent edits; no clean conflict signal to clients.
- Recommendation for our problem: **No** long-term; acceptable only until we schedule the fix.

## High level Architecture/Design

### Core entities/models

- `Message.version` — dedicated optimistic lock counter (not `updated_at`).
- `Message.updatedAt` / `updatedBy` — remain content-edit metadata only.
- `Message.deletedAt` / `deletedBy` — soft-delete metadata; any successful delete still increments `version`.

## Recommendation

1. Add a dedicated `messages.version` column with JPA `@Version` (do **not** reuse `updated_at`).
2. Translate optimistic lock failures to **409 Conflict** on message edit/delete.
3. Optionally teach the FE to reload the message and retry once on 409.
4. Defer implementation; track as a follow-up to Feature 15 message moderation (Phase 6 / 11). This doc is the design placeholder until then.

## Implementation details

- Added Flyway `V10__add_messages_version.sql`: `messages.version INTEGER NOT NULL DEFAULT 0` (existing rows backfill to 0). Java type is `Integer` (nullable on new transient instances).
- Added JPA `@Version` on `Message.version`. Left new in-memory instances `null` so Hibernate treats them as transient. `updated_at` stays business edit time.
  - Tức là KHÔNG init giá trị cho cột version = 0 (để nó = `null`), nếu không khi insert, Hibernate sẽ tưởng đây là đang update.
- `GlobalExceptionHandler` maps `OptimisticLockingFailureException` to HTTP **409** with `ErrorResponse` JSON and a stable `message` (no Hibernate details). The losing TX rolls back, including any `message_edit_history` insert.
- `MessageModerationService` is unchanged: `save` still flushes the versioned row; conflict surfaces at flush/commit.
- Tests: `GlobalExceptionHandlerTest` (409 body), `MessageModerationServiceTest` (edit/delete propagate lock failure).
- FE: no If-Match/`version` field yet. The 409 `ErrorResponse.message` is shown; reload-and-retry is still optional. See [`36_API_ERROR_RESPONSE.md`](./36_API_ERROR_RESPONSE.md).

Why it changed: concurrent edit/delete on the same row was last-write-wins and could skip history versions.

Rollout / backward-compatibility: `DEFAULT 0` backfill; old clients that ignore 409 still get a failed request instead of a silent overwrite. Re-run Flyway on each environment before deploying the entity change.

Test: when editing a message, here is the generated SQL:

```sql
UPDATE messages
SET content=?,deleted_at=?,deleted_by=?,group_id=?,message_type=?,timestamp=?,updated_at=?,updated_by=?,user_id=?,version=?
WHERE id=? AND version=?
```

## Lesson (look back here)

Same-row concurrent `save` without `@Version` is last-write-wins. A dedicated version column (not `updated_at`) makes the loser fail with 409 and rolls back edit history. Membership races (doc 23) still need a different lock.

## Future Higher-Scale Path

- If moderation volume grows or conflicts become common, keep optimistic locking and add idempotency keys for retries rather than switching to long-held pessimistic locks.
- Realtime fan-out of moderated messages (Feature 15 Task 12.4) should publish only after a successful versioned commit so clients never apply a losing edit.

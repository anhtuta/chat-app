# Message Moderation Optimistic Locking

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
- `MessageModerationService`
- Feature 15 message moderation APIs (`PATCH` / `DELETE /api/messages/{messageId}`)

**Related but different race:** concurrent kick/ban/role change vs edit/delete (auth TOCTOU on `group_participants`) — see [23_MESSAGE_MODERATION_MEMBERSHIP_AUTH_RACE.md](./23_MESSAGE_MODERATION_MEMBERSHIP_AUTH_RACE.md). Message `@Version` does not cover that case.

## Possible Solutions

### 1. Dedicated `@Version` column (optimistic locking)

- How it works: Add `messages.version BIGINT NOT NULL DEFAULT 0` and `@Version` on `Message`. Hibernate includes `version` in `UPDATE … WHERE id=? AND version=?` and increments on successful flush. Map `OptimisticLockException` / `ObjectOptimisticLockingFailureException` to HTTP 409; client refreshes and retries.
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

(Planned — not implemented yet.)

When implementing, record here:

- What changed (migration, entity, exception handling, tests, FE if any)
- Why it changed
- Rollout / migration / backward-compatibility notes (`DEFAULT 0` backfill, old clients ignoring 409, etc.)

## Future Higher-Scale Path

- If moderation volume grows or conflicts become common, keep optimistic locking and add idempotency keys for retries rather than switching to long-held pessimistic locks.
- Realtime fan-out of moderated messages (Feature 15 Task 12.4) should publish only after a successful versioned commit so clients never apply a losing edit.

# UTC Datetime and Instant Migration

## Current Problem

Almost all persisted datetimes in this app are **naive wall-clock values**, not absolute UTC instants.

- PostgreSQL columns use `timestamp(6)` (**without** time zone).
- JPA entities and DTOs use `java.time.LocalDateTime`.
- Writers call `LocalDateTime.now()`, which uses the **JVM default timezone**.
- Jackson serializes `LocalDateTime` as ISO-8601 **without** `Z` / offset (e.g. `"2026-08-04T12:00:00"`).
- The frontend parses those strings with `new Date(...)` / `Date.parse`, which treat missing-offset datetimes as **browser-local** time.

So a value is “whatever the JVM’s clock face said when it was written,” then reinterpreted in the client’s zone. That is not “stored in UTC.”

### Why this hurts

| Scenario                                                           | Risk                                                                                                                                     |
| ------------------------------------------------------------------ | ---------------------------------------------------------------------------------------------------------------------------------------- |
| JVM TZ ≠ client TZ (e.g. Docker `UTC`, browser `Asia/Ho_Chi_Minh`) | Relative times (“5min ago”) and absolute display skew by the offset                                                                      |
| Multiple app instances with different JVM TZs                      | Different wall-clock values for the same real moment; ordering / expiry drift                                                            |
| Expiry / TTL checks                                                | Server compares naive `expiresAt` to `LocalDateTime.now()`; clients may send / display absolute ISO with `Z` — disagreement across zones |
| Moving deploy TZ later (laptop → UTC container)                    | Historical rows become ambiguous; no zone was stored with them                                                                           |

### Already fixed (partial)

Join-link **`expires_at`** was migrated to absolute UTC end-to-end:

- DB: `timestamptz` (`V9__join_link_expires_at_timestamptz.sql`)
- Java: `Instant`
- API / FE: ISO-8601 with `Z`, compared to `Instant.now()` / `Date.now()`

Documented under Feature 15 (join links). The same entity still uses `LocalDateTime` for `createdAt` / `revokedAt`.

Exception responses already use `Instant.now()` for `timestamp` — inconsistent with the rest of the API.

### Inventory (as of this doc)

| Entity / table                                | Columns                                      | Java            | DB                |
| --------------------------------------------- | -------------------------------------------- | --------------- | ----------------- |
| `User` / `users`                              | `createdAt`                                  | `LocalDateTime` | `timestamp(6)`    |
| `Group` / `groups`                            | `createdAt`, `latestMessageAt`, `archivedAt` | `LocalDateTime` | `timestamp(6)`    |
| `GroupParticipant` / `group_participants`     | `joinedAt`                                   | `LocalDateTime` | `timestamp(6)`    |
| `Message` / `messages`                        | `timestamp`, `updatedAt`, `deletedAt`        | `LocalDateTime` | `timestamp(6)`    |
| `MessageEditHistory` / `message_edit_history` | `updatedAt`                                  | `LocalDateTime` | `timestamp(6)`    |
| `MessageMedia` / `message_media`              | `createdAt`, `updatedAt`                     | `LocalDateTime` | `timestamp(6)`    |
| `MediaUpload` / `media_uploads`               | `expiresAt`, `createdAt`, `updatedAt`        | `LocalDateTime` | `timestamp(6)`    |
| `GroupBan` / `group_bans`                     | `bannedAt`                                   | `LocalDateTime` | `timestamp(6)`    |
| `GroupJoinLink` / `group_join_links`          | `createdAt`, `revokedAt`                     | `LocalDateTime` | `timestamp(6)`    |
| `GroupJoinLink` / `group_join_links`          | `expiresAt`                                  | **`Instant`**   | **`timestamptz`** |

Related logic (not exhaustive):

- Entities: `@PrePersist` / `@PreUpdate` with `LocalDateTime.now()`
- Services: `MessageModerationService`, `MediaUploadSessionService`, `GroupMembershipService`, `MessageService` / latest-message CAS
- Repos: message cursor pagination (`beforeTimestamp`), `GroupRepository.updateLatestMessageIfNewer`
- DTOs / WS payloads: `MessageResponse`, `GroupResponse`, `GroupSummaryUpdate`, media prepare responses, etc.
- Frontend: `dateUtils.ts`, `ChatPage` `toEpochMillis`, join-link expiry UI (already Instant-aware for `expiresAt`)
- Seeders: `UserSeeder`, `GroupSeeder`, `MessageSeeder`

No app-level pin of `TZ` / `user.timezone` / `spring.jackson.time-zone` today.

## Possible Solutions

### 1. Migrate absolute moments to `Instant` + `timestamptz` (preferred)

- How it works: Treat every “point in time” column as an absolute instant. DB `timestamptz`, Java `Instant`, JSON ISO-8601 with `Z`. Writers use `Instant.now()`. Flyway alters columns with `AT TIME ZONE 'UTC'` (same pattern as V9), documenting that existing naive values are interpreted as UTC wall-clock.
- Pros: Correct across JVM and browser TZs; matches join-link precedent; clear API contract for FE; expiry / TTL / relative time become reliable.
- Cons: Broad touch surface (entities, DTOs, repos, tests, FE types, one or more Flyway migrations); need a phased rollout.
- Recommendation for our problem: **Yes**.

### 2. Keep `LocalDateTime` but force JVM + Jackson to UTC

- How it works: Pin `TZ=UTC` / `user.timezone=UTC`, set `spring.jackson.time-zone=UTC`, optionally customize serialization to append `Z` even for `LocalDateTime`.
- Pros: Smaller type churn; may reduce display skew if everyone assumes “naive means UTC.”
- Cons: Type still lies (`LocalDateTime` has no zone); DB still `timestamp without time zone`; easy to regress with one `LocalDateTime.now()` on a misconfigured host; weaker than `Instant` for expiry APIs.
- Recommendation for our problem: **No** as the end state. Acceptable only as a short interim harden (pin UTC in deploy) while migrating types.

### 3. Use `OffsetDateTime` / `ZonedDateTime` instead of `Instant`

- How it works: Persist offset or zone with the value.
- Pros: Can round-trip original offset if we ever need it.
- Cons: We only need absolute moments for chat/audit/expiry; `Instant` + format in client locale is simpler; PostgreSQL `timestamptz` stores UTC anyway.
- Recommendation for our problem: **No** for persisted chat timestamps.
- When I’d use it: User-facing calendar preferences, “meeting at 3pm in X zone,” scheduling with zone rules.

### 4. Frontend-only compensation

- How it works: Assume API naive strings are UTC and append `Z` before parsing.
- Pros: Tiny FE patch; can improve display quickly.
- Cons: Does not fix DB/Java correctness, multi-instance TZ drift, or server-side expiry; fragile if some payloads already have offsets.
- Recommendation for our problem: **No** as the full fix. Optional stopgap only after documenting the assumption.

### 5. Status quo

- How it works: Keep naive timestamps; rely on single-region same-TZ deploy.
- Pros: Zero cost.
- Cons: Already partially inconsistent (join-link `Instant` vs everything else); media upload TTL has the same class of bug join links had; display bugs when client TZ ≠ server JVM TZ.
- Recommendation for our problem: **No**.

## High level Architecture/Design

### Contract

- **Absolute moments** (send time, join time, ban time, archive time, edit/delete audit, upload session expiry, join-link create/revoke/expiry): `timestamptz` + `Instant` + ISO-8601 with offset/`Z`.
- **True local calendar values** (date-only birthday, “Tuesday 09:00 local” schedules): not used today; if needed later, use `LocalDate` / `LocalDateTime` deliberately — not for chat event times.
- **Display**: FE formats with `Intl` / relative helpers in the **user’s** locale/timezone from a correct absolute instant.
- **Comparisons**: server uses `Instant.now()`; FE uses `Date.now()` / epoch millis.

### Target column set (all current naive moment columns)

Same inventory table above — migrate every `LocalDateTime` moment column to `Instant`, including join-link `createdAt` / `revokedAt` for consistency.

### Migration pattern (per Flyway change)

Follow V9:

```sql
ALTER TABLE <table>
    ALTER COLUMN <col> TYPE timestamptz
    USING (
        CASE
            WHEN <col> IS NULL THEN NULL
            ELSE <col> AT TIME ZONE 'UTC'
        END
    );
```

Assumption: existing naive rows are **UTC wall-clock**. If production ever wrote under a non-UTC JVM, call that out before migrate and convert with the real source zone instead of `'UTC'`.

### Component / data flow (after migration)

```text
Writer (service / @PrePersist)
  -> Instant.now()
  -> JDBC / timestamptz (UTC)
  -> Entity Instant
  -> Jackson "2026-08-04T12:00:00Z"
  -> FE Date / epoch
  -> formatRelativeTime / formatAbsoluteTimeVi (local display)
```

## Recommendation

1. Treat this as a **phased correctness migration**, not a one-shot mega-PR.
2. **Pin deploy JVM to UTC** early (ops / Docker `TZ=UTC`) so new naive writes (until migrated) are at least consistent.
3. **Phase 1 — expiry / TTL:** migrate `media_uploads.expires_at` (and API fields `expiresAt` / `completeBy`) to `Instant` + `timestamptz`, mirroring join-link V9. Highest remaining correctness risk.
4. **Phase 2 — chat ordering & UX:** `messages.timestamp`, `groups.latest_message_at`, cursor `beforeTimestamp`, `GroupSummaryUpdate.latestMessageAt`, related DTOs/repos/tests/FE. Fixes relative time and sidebar “latest” comparisons across TZs.
5. **Phase 3 — audit / membership metadata:** `createdAt`, `joinedAt`, `bannedAt`, `archivedAt`, `updatedAt`, `deletedAt`, `revokedAt`, media created/updated, edit history, etc.
6. **Convention:** new moment columns must be `timestamptz` + `Instant` from day one; do not add more `timestamp` + `LocalDateTime` for events.
7. Defer coding until phases are scheduled; this doc is the design placeholder.

## Implementation details

(Planned — not implemented yet.)

## Future Higher-Scale Path

- Centralize “now” behind a small clock bean (`Clock` / `InstantSource`) for testability.
- Optional DB default `DEFAULT now()` on create columns once types are `timestamptz`.
- Multi-region: keep storing UTC instants only; never store per-region wall-clock in shared chat tables.
- If product later needs “show timestamps in group timezone,” that is a **display** preference on top of UTC storage — not a reason to store naive local times.

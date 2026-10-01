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
Phase 3 later migrated `createdAt` / `revokedAt` too, so join-link datetime fields are now fully on `Instant`.

Media upload session **`expires_at`** is now also migrated end-to-end:

- DB: `timestamptz` (`V15__media_upload_expires_at_timestamptz.sql`)
- Java: `Instant`
- API / FE: prepare-upload response fields `expiresAt` serialize as ISO-8601 with `Z`, compared to `Instant.now()` in backend expiry checks

Phase 3 later migrated `createdAt` / `updatedAt` too, so media-upload session datetime fields are now fully on `Instant`.

Chat timeline ordering fields are now also migrated end-to-end:

- DB: `messages.timestamp`, `groups.latest_message_at` as `timestamptz` (`V16__messages_timestamp_and_groups_latest_message_at_timestamptz.sql`)
- Java: `Instant`
- API / WS / FE: `MessageResponse.timestamp`, `MessageResponse.freshnessKey`, `GroupResponse.latestMessageAt`, and `GroupSummaryUpdate.latestMessageAt` now serialize as ISO-8601 with `Z`

Phase 3 later migrated `messages.updatedAt`, `messages.deletedAt`, and the remaining audit fields too, so the timeline path is now fully on `Instant`.

Exception responses already use `Instant.now()` for `timestamp` and now align with the rest of the API contract.

### Inventory (as of this doc)

| Entity / table                                | Columns                   | Java            | DB                |
| --------------------------------------------- | ------------------------- | --------------- | ----------------- |
| `User` / `users`                              | `createdAt`               | **`Instant`**   | **`timestamptz`** |
| `Group` / `groups`                            | `createdAt`, `archivedAt` | **`Instant`**   | **`timestamptz`** |
| `Group` / `groups`                            | `latestMessageAt`         | **`Instant`**   | **`timestamptz`** |
| `GroupParticipant` / `group_participants`     | `joinedAt`                | **`Instant`**   | **`timestamptz`** |
| `Message` / `messages`                        | `timestamp`               | **`Instant`**   | **`timestamptz`** |
| `Message` / `messages`                        | `updatedAt`, `deletedAt`  | **`Instant`**   | **`timestamptz`** |
| `MessageEditHistory` / `message_edit_history` | `updatedAt`               | **`Instant`**   | **`timestamptz`** |
| `MessageMedia` / `message_media`              | `createdAt`, `updatedAt`  | **`Instant`**   | **`timestamptz`** |
| `MediaUpload` / `media_uploads`               | `createdAt`, `updatedAt`  | **`Instant`**   | **`timestamptz`** |
| `MediaUpload` / `media_uploads`               | `expiresAt`               | **`Instant`**   | **`timestamptz`** |
| `GroupBan` / `group_bans`                     | `bannedAt`                | **`Instant`**   | **`timestamptz`** |
| `GroupJoinLink` / `group_join_links`          | `createdAt`, `revokedAt`  | **`Instant`**   | **`timestamptz`** |
| `GroupJoinLink` / `group_join_links`          | `expiresAt`               | **`Instant`**   | **`timestamptz`** |

Related logic (not exhaustive):

- Entities: `@PrePersist` / `@PreUpdate` with `Instant.now()`
- Services: `MessageModerationService`, `MediaUploadSessionService`, `GroupMembershipService`, `MessageService` / latest-message CAS
- Repos: message cursor pagination (`beforeTimestamp`), `GroupRepository.updateLatestMessageIfNewer`
- DTOs / WS payloads: `MessageResponse`, `GroupResponse`, `GroupSummaryUpdate`, media prepare responses, etc.
- Frontend: `dateUtils.ts`, `ChatPage` `toEpochMillis`, join-link expiry UI (already Instant-aware for `expiresAt`)
- Seeders: `UserSeeder`, `GroupSeeder`, `MessageSeeder`

Phase 0 now pins the backend container runtime to UTC via `chat-app-backend/Dockerfile`
(`TZ=UTC`, `JAVA_TOOL_OPTIONS=-Duser.timezone=UTC`). This reduces new naive-write drift
during the migration window, but it does **not** change the persisted schema/types yet.

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
3. **Phase 1 — expiry / TTL:** migrate `media_uploads.expires_at` (and API fields `expiresAt`) to `Instant` + `timestamptz`, mirroring join-link V9. Highest remaining correctness risk.
4. **Phase 2 — chat ordering & UX:** `messages.timestamp`, `groups.latest_message_at`, cursor `beforeTimestamp`, `GroupSummaryUpdate.latestMessageAt`, related DTOs/repos/tests/FE. Fixes relative time and sidebar “latest” comparisons across TZs.
5. **Phase 3 — audit / membership metadata:** `createdAt`, `joinedAt`, `bannedAt`, `archivedAt`, `updatedAt`, `deletedAt`, `revokedAt`, media created/updated, edit history, etc.
6. **Convention:** new moment columns must be `timestamptz` + `Instant` from day one; do not add more `timestamp` + `LocalDateTime` for events.
7. Track completed rollout work in `Implementation details`; keep `Recommendation` focused on the intended end state.

## Implementation details

### Phase 0 - rollout guardrails and legacy-data check - **Done**

- What changed:
  - Implemented the repo-side UTC runtime pin in `chat-app-backend/Dockerfile`:
    - `TZ=UTC`
    - `JAVA_TOOL_OPTIONS=-Duser.timezone=UTC`
  - Re-verified that `application.yaml` still has no `hibernate.jdbc.time_zone` or Jackson timezone pin; this phase intentionally solves the `LocalDateTime.now()` writer problem first instead of pretending serialization settings fix it.
  - Kept this doc as the migration source of truth and recorded Phase 0 here instead of rewriting the recommendation.
  - Documented the remaining legacy-data assumption for future Flyway conversions:
    existing naive DB values will be treated as UTC wall-clock unless evidence shows a different historical JVM timezone.
- Why it changed:
  - We need a safe baseline before touching DB types. Even after we migrate one feature area, many remaining writes still call `LocalDateTime.now()` and can drift if JVM timezone differs between environments.
- Rollout, migration, or backward-compatibility notes:
  - Status: implemented in repo for Docker-based runtime paths.
  - This phase is intentionally low-risk and can land before any Flyway migration.
  - Remaining manual check: if we discover production rows were historically written under a non-UTC JVM, we must stop and replace `AT TIME ZONE 'UTC'` with the real source zone for the affected migration.
  - Local non-Docker runs still inherit the host timezone unless launched with an explicit UTC JVM setting.

### Phase 1 - media upload session expiry / TTL - **Done**

- What changed:
  - Migrated `media_uploads.expires_at` from `timestamp(6)` to `timestamptz` in `V15__media_upload_expires_at_timestamptz.sql`, following the same `AT TIME ZONE 'UTC'` pattern as `V9__join_link_expires_at_timestamptz.sql`.
  - Converted the expiry path from `LocalDateTime` to `Instant` in:
    - entity: `MediaUpload.expiresAt`
    - service: `MediaUploadSessionService` (`prepareUploadSession`, `ensureNotExpired`, `createUploadRecord`)
    - DTOs returned to FE: `PrepareMediaMessageResponse.expiresAt`, `PreparedMediaAttachmentResponse.expiresAt`
    - tests covering prepare-response expiry propagation and expired-session rejection
  - Kept `media_uploads.created_at` and `media_uploads.updated_at` unchanged for a later phase so Phase 1 stays focused on TTL correctness only.
- Why it changed:
  - This was the highest remaining correctness risk after join-link expiry. The old code computed expiry with `LocalDateTime.now().plusMinutes(...)` and validated with `isBefore(LocalDateTime.now())`, so TTL behavior depended on JVM timezone.
- Rollout, migration, or backward-compatibility notes:
  - Migration used the V9 pattern:

```sql
ALTER TABLE media_uploads
    ALTER COLUMN expires_at TYPE timestamptz
    USING (
        CASE
            WHEN expires_at IS NULL THEN NULL
            ELSE expires_at AT TIME ZONE 'UTC'
        END
    );
```

- JSON field names stayed the same; only the serialized format changed from naive ISO to ISO with `Z`.
- The prepared-attachment response now uses `expiresAt` so both session-level and attachment-level expiry fields share the same name and instant semantics.

### Phase 2 - chat timeline ordering and sidebar freshness - **Done**

- What changed:
  - Migrated timeline/order-defining columns to absolute instants in `V16__messages_timestamp_and_groups_latest_message_at_timestamptz.sql`:
    - `messages.timestamp`
    - `groups.latest_message_at`
  - Converted the backend message/history pipeline from `LocalDateTime` to `Instant` in:
    - entities / DTOs: `Message.timestamp`, `MessageResponse.timestamp`, `GroupResponse.latestMessageAt`, `GroupSummaryUpdate.latestMessageAt`
    - controller/service/repo cursor flow: `MessageController.beforeTimestamp`, `MessageHistoryService`, `MessageRepository.findGroupMessageIdsBeforeCursor`
    - group latest-message CAS logic: `GroupRepository.updateLatestMessageIfNewer`, `updateLatestMessageIfNotStale`
    - freshness-key generation in `MessageResponse`
  - Kept FE sort/merge/parsing code unchanged because it already consumed timestamp strings via `Date.parse(...)` / `new Date(...)`; once the backend started emitting ISO-8601 with `Z`, those paths became correct without compensating logic.
- Why it changed:
  - These paths decide message ordering, cursor pagination, sidebar recency, and unread movement. They were the most user-visible timezone-sensitive behavior after upload expiry.
- Rollout, migration, or backward-compatibility notes:
  - DB migration and API type changes shipped together so cursor queries and latest-message comparisons now speak the same instant format.
  - During Phase 2 only, `MessageResponse.freshnessKey` temporarily bridged mixed timestamp types; Phase 3 removed that bridge once the remaining audit fields also moved to `Instant`.
  - Backend test sources compile cleanly after the type migration. Full runtime test execution is still subject to the existing local Mockito/Byte Buddy attach issue on Java 25.

### Phase 3 - audit, lifecycle, and membership metadata - **Done**

- What changed:
  - Migrated all remaining persisted event/audit moments from `LocalDateTime` to `Instant` in `V17__remaining_audit_timestamps_timestamptz.sql`, including:
    - `users.created_at`
    - `groups.created_at`, `groups.archived_at`
    - `group_participants.joined_at`
    - `group_bans.banned_at`
    - `group_join_links.created_at`, `group_join_links.revoked_at`
    - `messages.updated_at`, `messages.deleted_at`
    - `message_edit_history.updated_at`
    - `message_media.created_at`, `message_media.updated_at`
    - `media_uploads.created_at`, `media_uploads.updated_at`
  - Updated corresponding entities, response DTOs, JDBC bulk insert for `group_participants`, service methods, seeders, and tests.
  - Replaced remaining persisted-moment writers such as `@PrePersist`, `@PreUpdate`, and service-level `LocalDateTime.now()` calls with `Instant.now()`.
  - Finished the public API migration for audit/lifecycle fields such as:
    - `UserResponse.createdAt`
    - `GroupResponse.createdAt`
    - `GroupMemberResponse.joinedAt`
    - `GroupBanResponse.bannedAt`
    - `GroupJoinLinkResponse.createdAt`, `GroupJoinLinkResponse.revokedAt`
    - `MessageResponse.updatedAt`, `MessageResponse.deletedAt`
- Why it changed:
  - This finishes the consistency story. After Phase 2, the app rendered and ordered messages correctly, but audit/history metadata was still a mix of naive and absolute timestamps until this phase completed the migration.
- Rollout, migration, or backward-compatibility notes:
  - Used the same Flyway `AT TIME ZONE 'UTC'` conversion pattern for every migrated legacy column.
  - Backend source and test-source compilation now pass with the Phase 3 types. Full runtime test execution is still subject to the existing local Mockito/Byte Buddy attach issue on Java 25.

### Phase 4 - cleanup, guardrails, and regression prevention - **Done**

- What changed:
  - Removed the temporary entity compatibility setters/helpers that still accepted `LocalDateTime` during the phased rollout. Persisted event-time fields now accept `Instant` only in the main backend model.
  - Updated remaining backend tests and setup code to construct absolute moments directly with `Instant` rather than relying on transitional wall-clock inputs.
  - Re-checked repository and JDBC call sites so persisted-moment write paths consistently use `Instant`, including the `group_participants` bulk insert timestamp.
  - Updated this feature doc so the completed migration and cleanup are recorded in one place.
- Why it changed:
  - The biggest remaining regression risk after Phases 1-3 was silent drift back toward naive wall-clock timestamps through test helpers or overloaded setters. Removing those escape hatches makes the `Instant` contract explicit and harder to accidentally bypass.
- Rollout, migration, or backward-compatibility notes:
  - No DB contract change was needed in Phase 4; this is source-level cleanup and verification only.
  - Verification completed:
    - backend source and test-source compile: `mvn -DskipTests compile test-compile`
    - targeted changed unit tests passed: `MessageResponseTest`, `MessageResponseMapperTest`, `MessageServiceTest`, `GroupServiceTest`, `GroupProfileRealtimePublisherTest`, `GroupMembershipRealtimePublisherTest`, `GroupAuthorizationServiceTest`, `GroupMembershipServiceTest`, `MediaUploadSessionServiceTest`, `MessageModerationServiceTest`
  - The backend codebase now has no remaining `LocalDateTime` usages under `chat-app-backend/src/main/java` or `chat-app-backend/src/test/java`.

## Future Higher-Scale Path

- Centralize “now” behind a small clock bean (`Clock` / `InstantSource`) for testability.
- Optional DB default `DEFAULT now()` on create columns once types are `timestamptz`.
- Multi-region: keep storing UTC instants only; never store per-region wall-clock in shared chat tables.
- If product later needs “show timestamps in group timezone,” that is a **display** preference on top of UTC storage — not a reason to store naive local times.

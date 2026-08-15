# Last-Member Leave vs Concurrent Join

Severity: 🟠 Major

## Current Problem

Last-member `leaveGroup` archives the group after an unlocked active check + member count. Concurrent `addMember` / `joinByToken` can pass their own active check before the archive commits, then insert a participant into a group that is about to be archived — leaving an archived group with members.

Classic check-then-act (TOCTOU) on shared group lifecycle state.

Related code: `GroupMembershipService.leaveGroup`, `addMember`, `joinByToken`.

### Examples (before the fix)

Last-member leave is when `countByGroupId <= 1`: the remaining participant (usually the solo `LEADER`) archives the group instead of transferring leadership. Outsiders can still `joinByToken` without being members; the leader can also `addMember` from another tab.

Before `lockActiveGroup`, leave/add/join each did an **unlocked** “is the group still active?” read, then counted or inserted. Those TXs did not share a mutex on `groups`.

#### 1. Solo leader leaves while someone joins by token

Alice is the only member (`LEADER`). Charlie has a valid join link.

1. Alice starts `POST …/leave`.
2. Leave loads the group as active (no row lock), sees `memberCount == 1`, decides to archive.
3. Charlie’s concurrent `POST …/join-links/{token}/join` also sees `archivedAt = null`, then inserts Charlie as `MEMBER`.
4. Alice’s leave sets `archived_at` / `archived_by`, deletes Alice’s participant row, and commits.

**Broken outcome:** Group is archived **and** still has Charlie as a member. Archived groups are hidden from normal lists, but a participant row exists; Charlie may still appear as a member of a dead group.

#### 2. Solo leader adds a member while leaving

Alice is the only member. She has `ADD_MEMBERS` as `LEADER`.

1. Alice starts `POST …/members` (add Charlie) on one tab — unlocked active check passes.
2. Alice’s other tab starts leave — also sees active + count 1, will archive.
3. Add inserts Charlie and commits (or interleaves the other way: leave archives first, add still inserts if it does not re-check archive after the unlocked read).
4. Leave archives and removes Alice.

**Broken outcome:** Same as (1): archived group with Charlie still in `group_participants`.

Case này hầu như KHÔNG xảy ra trong thực tế (trừ bot), dù lý thuyết là có thể xảy ra.

#### 3. Two join-by-token requests racing last-member leave

Alice is the only member and is leaving. Dana and Eve both redeem the same (or two) join links.

1. Leave, Dana’s join, and Eve’s join all pass “group is active.”
2. Dana and Eve insert participant rows.
3. Leave archives and deletes Alice.

**Broken outcome:** Archived group with one or more new members. Join did not serialize with archive.

These are TOCTOU: **time of check** = unlocked `archivedAt` / member count; **time of use** = `INSERT` participant or `UPDATE` archive on `groups` without a shared lock.

### After the fix

`leaveGroup`, `addMember`, and `joinByToken` all call `lockActiveGroup` (`SELECT … FOR UPDATE` + `ensureActive`) **before** count / insert / archive. They serialize on the same `groups` row.

| Example                     | What happens after the fix                                                                                                                                                                                                                                      |
| --------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1. Leave vs join-by-token   | Whichever gets the lock first wins. If leave archives first, Charlie’s `lockActiveGroup` fails (`ensureActive`). If Charlie joins first, `memberCount > 1` and Alice cannot last-member-archive (as `LEADER` with others she must transfer leadership instead). |
| 2. Leave vs Alice addMember | Same lock. Add-then-leave: Alice is no longer last member → leave rejected until transfer. Leave-then-add: group already archived → add fails.                                                                                                                  |
| 3. Two joins vs leave       | All three queue on the group row; after archive, later joins hit `ensureActive` and do not insert.                                                                                                                                                              |

## Possible Solutions

### 1. Pessimistic lock on the group row (chosen)

- How it works: `GroupRepository.findByIdForUpdate` (`SELECT … FOR UPDATE`). Leave, add, and self-join all lock the group, re-check `archivedAt`, then count / insert / archive under that lock.
- Pros: Simple; serializes the critical section across instances; matches “lifecycle row” ownership.
- Cons: Hold a row lock for the TX (keep those TXs short).
- Recommendation: **Yes**.

### 2. Optimistic `@Version` on `Group`

- How it works: bump version on archive; add/join fail and retry if stale.
- Pros: No long-held locks.
- Cons: More retry UX; easy to miss a write path that should bump version.
- Recommendation: **No** here (membership lifecycle is low QPS; lock is clearer).

## Recommendation

Always lock the **same** group row in every path that pairs “is this group still active?” with a membership write or archive. Do not trust an earlier unlocked `requireActivePermission` / `ensureActive` alone.

## Implementation details

- Added `GroupRepository.findByIdForUpdate`.
- Membership/role mutations use `lockActiveGroup` **before** authorization (see [24_MEMBERSHIP_MUTATION_AUTH_LOCK_ORDER.md](./24_MEMBERSHIP_MUTATION_AUTH_LOCK_ORDER.md)), which also covers last-member leave vs add/join.
- Docs note in Feature 15 Phase 3.

## Lesson (look back here)

When two transactions both do **read active flag → decide → write membership/archive**, they need a shared lock or atomic CAS. Auth checks alone are not a concurrency control. Same pattern as “don’t RMW without a lock” in `.cursor/rules/avoid-race-conditions.instructions.mdc`.

# Last-Member Leave vs Concurrent Join

## Current Problem

Last-member `leaveGroup` archives the group after an unlocked active check + member count. Concurrent `addMember` / `joinByToken` can pass their own active check before the archive commits, then insert a participant into a group that is about to be archived — leaving an archived group with members.

Classic check-then-act (TOCTOU) on shared group lifecycle state.

Related code: `GroupMembershipService.leaveGroup`, `addMember`, `joinByToken`.

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
- `leaveGroup`, `addMember`, and `joinByToken` call `lockGroupForLifecycleUpdate` before active re-check + membership mutation.
- Docs note in Feature 15 Phase 3.

## Lesson (look back here)

When two transactions both do **read active flag → decide → write membership/archive**, they need a shared lock or atomic CAS. Auth checks alone are not a concurrency control. Same pattern as “don’t RMW without a lock” in `.cursor/rules/avoid-race-conditions.instructions.mdc`.

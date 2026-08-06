# Membership Mutation Auth Before Lock (TOCTOU)

## Current Problem

`addMember` (and similar mutations) called `requireActivePermission` **before** `findByIdForUpdate`. A concurrent demotion/kick could revoke the actor's role after that check and before the membership write - e.g. a former privileged member still adds a member.

`joinByToken` had the same check-then-act shape for join-link state: it validated a token before taking the group lock, so a concurrent `revokeJoinLink` could commit before the participant insert unless the token was revalidated after the lock.

Same TOCTOU family as [21_LAST_MEMBER_LEAVE_CONCURRENT_JOIN.md](./21_LAST_MEMBER_LEAVE_CONCURRENT_JOIN.md) (archive vs join) and [23_MESSAGE_MODERATION_MEMBERSHIP_AUTH_RACE.md](./23_MESSAGE_MODERATION_MEMBERSHIP_AUTH_RACE.md) (moderation vs kick), but here both sides are membership APIs on the **group** row.

| Race                                    | What went wrong                                        |
| --------------------------------------- | ------------------------------------------------------ |
| add / create-link / ban / ... vs demote | Auth saw old role; write ran after role change         |
| join-by-token vs revoke-link            | Token looked valid; join ran after revocation          |
| leave/archive vs add (doc 21)           | Active check vs insert - already fixed with group lock |

### Examples (before the fix)

Role rules from [15_GROUP_ROLES_AND_PERMISSIONS.md](./15_GROUP_ROLES_AND_PERMISSIONS.md): nobody can kick/ban/promote/demote the `LEADER` via manage-target APIs; a leader steps down only via `transferLeadership`. So races below use actors who **can** lose privilege mid-flight (`CO_LEADER` / `ELDER`), or leadership transfer for the leader case.

Before the fix, membership mutations authorized or validated mutable state **first**, then acquired the group lock (or wrote without sharing that lock with role/link changes).

#### 1. Demote co-leader then they still add a member

Alice is `CO_LEADER` (has `ADD_MEMBERS`). Bob is `LEADER` (can demote Alice).

1. Alice starts `POST …/members` (add Charlie).
2. Alice’s request passes `requireActivePermission(ADD_MEMBERS)` while she is still `CO_LEADER`.
3. Bob’s concurrent `PATCH …/role` demotes Alice to `MEMBER` and commits.
4. Alice’s request continues, locks/inserts Charlie as a member, and succeeds.

**Broken outcome:** Charlie joins even though Alice is now `MEMBER` and no longer has `ADD_MEMBERS`.

#### 2. Leadership transfer then former leader still bans

Alice is `LEADER`. Only she can transfer leadership (which demotes her to `MEMBER`).

1. Alice starts `POST …/bans` against Dave.
2. Auth passes (`BAN_MEMBERS` / manage-target) while Alice is still `LEADER`.
3. Alice’s other device completes `transferLeadership` to Bob; Alice is now `MEMBER`.
4. Alice’s ban request continues and persists the ban (and may remove Dave).

**Broken outcome:** A former leader who just stepped down still completes a ban as `MEMBER`.

#### 3. Revoke join link while someone is joining

Alice has a valid join token. Bob is `LEADER` or `CO_LEADER` (has `CREATE_JOIN_LINK`, so can revoke join links).

1. Alice starts `POST .../join-links/{token}/join`.
2. Alice's request loads the join link and sees `revokedAt = null`.
3. Bob's concurrent `DELETE .../join-links/{joinLinkId}` takes the group lock, sets `revokedAt`, and commits.
4. Alice's request continues, inserts Alice as a member, and succeeds if it does not refresh/revalidate the link after acquiring the same lock.

**Broken outcome:** A revoked invite still admits a new member.

#### 4. Kick co-leader mid-create-join-link

Alice is `CO_LEADER` or `ELDER` (has `CREATE_JOIN_LINK`). Bob is `LEADER` (can kick Alice; he cannot kick the leader).

1. Alice starts `POST …/join-links`.
2. Auth passes `CREATE_JOIN_LINK` while she is still a privileged member.
3. Bob kicks Alice; her `group_participants` row is deleted and commits.
4. Alice’s request continues and inserts a join link for the group.

**Broken outcome:** A removed member still creates an active invite.

#### 5. Leader demotes actor mid-role change

Alice is `CO_LEADER` (has `MANAGE_ROLES`). Eve is `ELDER`. Bob is `LEADER`.

1. Alice starts demoting Eve (`PATCH …/role`).
2. Auth passes (`MANAGE_ROLES` + manage-target) while Alice is still `CO_LEADER`.
3. Bob demotes or kicks Alice and commits.
4. Alice’s demote of Eve still saves.

**Broken outcome:** Alice changes Eve's role after Alice's own privilege was already revoked.

These are check-then-act (TOCTOU): **time of check** = unlocked permission/link-state read; **time of use** = membership/link/role write after a concurrent mutation changed what is allowed.

### After the fix (how these are prevented)

Every membership/role mutation takes the **same** group row lock (`lockActiveGroup` -> `findByIdForUpdate`) before authorization or final mutable-state validation, then writes under that lock:

| Example                         | What happens after the fix                                                                                                                                                      |
| ------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1. Demote vs add                | Bob's demote and Alice's add serialize on the group lock. Whichever runs second re-reads Alice's role; if demoted to `MEMBER`, `ADD_MEMBERS` fails and Charlie is not inserted. |
| 2. Transfer vs ban              | Transfer and ban serialize. After transfer, Alice's ban recheck sees she is `MEMBER` / lacks permission and fails.                                                              |
| 3. Revoke vs join               | Revoke and join serialize. After a revoke wins, join refreshes and revalidates the link under the lock, sees `revokedAt`, and does not insert Alice.                            |
| 4. Kick vs create join link     | Kick and create-link serialize. After kick, Alice is not a member; `CREATE_JOIN_LINK` fails and no link is saved.                                                               |
| 5. Demote actor mid-role change | Both mutations take the same lock; after Bob's demote/kick, Alice's auth fails and Eve's role is not changed.                                                                   |

Read-only list endpoints stay unlocked (stale lists are acceptable). Archive vs join remains covered by the same lock (see doc 21).

## Fix (implemented)

Shared helper `lockActiveGroup(groupId)` = `findByIdForUpdate` + `ensureActive`.

**Order for every membership/role mutation:**

1. `lockActiveGroup`
2. Authorize / recheck permission (fresh participant role under the lock)
3. Save membership / ban / role / archive state

`joinByToken` also refreshes and revalidates the loaded `GroupJoinLink` after `lockActiveGroup` so `revokeJoinLink` cannot win the lock and still let the waiting join insert from stale token state.

Applies to: `addMember`, `createJoinLink`, `revokeJoinLink`, `joinByToken` (link refresh/revalidation and ban recheck after lock), `kickMember`, `banMember`, `unbanMember`, `updateMemberRole`, `transferLeadership`, `leaveGroup`.

## Lesson

A permission or state check is only as strong as the lock (or CAS) that covers it **and** the write. "Auth/validate then lock then write" still races with anyone who mutates roles or link state under that lock - so the waiting transaction must re-read the state after it acquires the lock.

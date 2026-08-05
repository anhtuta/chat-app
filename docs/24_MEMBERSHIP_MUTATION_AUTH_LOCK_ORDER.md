# Membership Mutation Auth Before Lock (TOCTOU)

## Current Problem

`addMember` (and similar mutations) called `requireActivePermission` **before** `findByIdForUpdate`. A concurrent demotion/kick could revoke the actor’s role after that check and before the membership write — e.g. a former leader still adds a member.

Same TOCTOU family as [21_LAST_MEMBER_LEAVE_CONCURRENT_JOIN.md](./21_LAST_MEMBER_LEAVE_CONCURRENT_JOIN.md) (archive vs join) and [23_MESSAGE_MODERATION_MEMBERSHIP_AUTH_RACE.md](./23_MESSAGE_MODERATION_MEMBERSHIP_AUTH_RACE.md) (moderation vs kick), but here both sides are membership APIs on the **group** row.

| Race                                  | What went wrong                                        |
| ------------------------------------- | ------------------------------------------------------ |
| add / create-link / ban / … vs demote | Auth saw old role; write ran after role change         |
| leave/archive vs add (doc 21)         | Active check vs insert — already fixed with group lock |

### Examples (before the fix)

Role rules from [15_GROUP_ROLES_AND_PERMISSIONS.md](./15_GROUP_ROLES_AND_PERMISSIONS.md): nobody can kick/ban/promote/demote the `LEADER` via manage-target APIs; a leader steps down only via `transferLeadership`. So races below use actors who **can** lose privilege mid-flight (`CO_LEADER` / `ELDER`), or leadership transfer for the leader case.

Before the fix, membership mutations authorized **first**, then acquired the group lock (or wrote without sharing that lock with role changes).

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

#### 3. Kick co-leader mid–create-join-link

Alice is `CO_LEADER` / `ELDER` (has `CREATE_JOIN_LINK`). Bob is `LEADER` (can kick Alice; he cannot kick the leader).

1. Alice starts `POST …/join-links`.
2. Auth passes `CREATE_JOIN_LINK` while she is still a privileged member.
3. Bob kicks Alice; her `group_participants` row is deleted and commits.
4. Alice’s request continues and inserts a join link for the group.

**Broken outcome:** A removed member still creates an active invite.

#### 4. Leader demotes actor mid–role change

Alice is `CO_LEADER` (has `MANAGE_ROLES`). Eve is `ELDER`. Bob is `LEADER`.

1. Alice starts demoting Eve (`PATCH …/role`).
2. Auth passes (`MANAGE_ROLES` + manage-target) while Alice is still `CO_LEADER`.
3. Bob demotes or kicks Alice and commits.
4. Alice’s demote of Eve still saves.

**Broken outcome:** Privilege changes apply after the actor’s own privilege was already revoked — last writer “wins” on stale auth.

These are check-then-act (TOCTOU): **time of check** = unlocked permission read; **time of use** = membership/role write after a concurrent mutation changed who is allowed.

### After the fix (how these are prevented)

Every membership/role mutation takes the **same** group row lock (`lockActiveGroup` → `findByIdForUpdate`) **before** authorization, then writes under that lock:

| Example                         | What happens after the fix                                                                                                                                           |
| ------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1. Demote vs add                | Bob’s demote and Alice’s add serialize on the group lock. Whichever runs second re-reads Alice’s role; if demoted to `MEMBER`, `ADD_MEMBERS` fails and Charlie is not inserted. |
| 2. Transfer vs ban              | Transfer and ban serialize. After transfer, Alice’s ban recheck sees she is `MEMBER` / lacks permission and fails.                                                   |
| 3. Kick vs create join link     | Kick and create-link serialize. After kick, Alice is not a member; `CREATE_JOIN_LINK` fails and no link is saved.                                                    |
| 4. Demote actor mid–role change | Both mutations take the same lock; after Bob’s demote/kick, Alice’s auth fails and Eve’s role is not changed.                                                        |

Read-only list endpoints stay unlocked (stale lists are acceptable). Archive vs join remains covered by the same lock (see doc 21).

## Fix (implemented)

Shared helper `lockActiveGroup(groupId)` = `findByIdForUpdate` + `ensureActive`.

**Order for every membership/role mutation:**

1. `lockActiveGroup`
2. Authorize / recheck permission (fresh participant role under the lock)
3. Save membership / ban / role / archive state

Applies to: `addMember`, `createJoinLink`, `revokeJoinLink`, `joinByToken` (ban recheck after lock), `kickMember`, `banMember`, `unbanMember`, `updateMemberRole`, `transferLeadership`, `leaveGroup`.

## Lesson

A permission check is only as strong as the lock (or CAS) that covers it **and** the write. “Auth then lock then write” still races with anyone who mutates roles without that lock — so role mutations must take the **same** lock too.

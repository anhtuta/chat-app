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

## How does it work?

What we did is to lock a row in table `groups`, not `group_participants`: `Group group = lockActiveGroup(groupId);`. But why it works?

The key idea is: the `groups` row lock is **not protecting the `group_participants` table by itself**. It is acting as a **shared mutex for all membership/role mutations of one group**.

You can see that both sides of the race now do this first:

- `addMember()` locks the group before auth:

  ```java
  // GroupMembershipService.java
  public GroupMemberResponse addMember(User actor, Long groupId, Long userId) {
      // Lock before auth so a concurrent demotion cannot leave a former leader authorized to add.
      Group group = lockActiveGroup(groupId);
      groupAuthorizationService.requireActivePermission(actor, groupId, GroupPermission.ADD_MEMBERS);
      User target = loadUser(userId);
      groupAuthorizationService.requireNotBanned(target, groupId);
  ```

- `updateMemberRole()` also locks the **same** group row before changing roles:
  ```java
  // GroupMembershipService.java
  public GroupMemberResponse updateMemberRole(User actor, Long groupId, Long userId, GroupRole role) {
      // ...
      Group group = lockActiveGroup(groupId);
      User target = loadUser(userId);
      groupAuthorizationService.requireCanManageTarget(actor, groupId, target, GroupPermission.MANAGE_ROLES);
      GroupParticipant targetParticipant = loadParticipant(groupId, userId);
  ```

And `lockActiveGroup()` is explicitly documented as the serialization point:

```java
// GroupMembershipService.java
/**
 * Acquires a pessimistic write lock on the group lifecycle row and ensures the group is active.
 *
 * All membership/role mutations must call this before authorization so concurrent
 * demotion/kick/archive cannot invalidate a permission check that already passed.
 */
private Group lockActiveGroup(Long groupId) {
    Group group = lockGroupForLifecycleUpdate(groupId);
    ensureActive(group);
    return group;
}
```

Why this works:

1. Alice calls `addMember(groupId=1)` and tries to lock `groups.id = 1`.
2. Bob calls `updateMemberRole(groupId=1)` and also tries to lock `groups.id = 1`.
3. Only one transaction can hold that row lock at a time.
4. The loser waits.
5. After the winner commits, the loser continues and **then** reads `group_participants` for auth.

That last part is important: the role is still read from `group_participants`, via `requireActivePermission()` -> `requirePermission()` -> `requireMember()`:

```java
// GroupAuthorizationService.java
public Group requireActivePermission(User user, Long groupId, GroupPermission permission) {
    Group group = requirePermission(user, groupId, permission);
    if (group.getArchivedAt() != null) {
        throw new BadRequestException("Group is archived");
    }
    return group;
}

public Group requirePermission(User user, Long groupId, GroupPermission permission) {
    GroupParticipant participant = requireMember(user, groupId);
    if (!hasPermission(resolveRole(participant), permission)) {
        throw new ForbiddenException("You do not have permission to " + permission.name().toLowerCase());
    }
    return participant.getGroup();
}
```

So the lock is on `groups`, but the **fresh role read happens after serialization**, which is what prevents stale auth.

In other words:

- we are **not** saying "the role row is physically locked by locking `groups`"
- we are saying "every code path that can change or rely on that role must pass through the same group lock first"

That makes the `groups` row a coarse-grained per-group mutex.

For your example:

1. Alice starts `addMember`, but must first lock group `G`.
2. Bob starts demoting Alice, and must also first lock group `G`.
3. If Bob gets the lock first, he commits Alice -> `MEMBER`.
4. Alice gets the lock after Bob commits.
5. Alice's `requireActivePermission(ADD_MEMBERS)` now rereads her participant row and sees `MEMBER`.
6. The add fails.

The guarantee breaks only if some other code path modifies `group_participants` for that group **without first taking the same group lock**. That is why the doc keeps stressing "all membership/role mutations must take the same lock."

## Can we replace this with Redis lock?

Yes, you can use Redis as the shared lock.

But the important rule is:

- use **one shared lock mechanism consistently** for all membership/role mutations of the same group
- do **not** mix "some paths use Redis lock" and "some paths use DB row lock" and assume they coordinate

If you switch to a Redis lock for this problem, then for these race conditions you do **not need** the `groups` table row lock anymore, **provided that**:

- every relevant mutation path takes the same Redis lock key, such as `group:mutation:lock:{groupId}`
- the permission check happens only **after** that lock is acquired
- the DB write still happens inside a normal DB transaction
- DB constraints still protect invariants like "one leader" and unique membership

So Redis can replace the current `findByIdForUpdate` lock as the per-group mutex.

### Important caveat

If you use Redis, do **not** unlock in `finally` inside the same `@Transactional` method.

Why:

- method `finally` runs before Spring commits the DB transaction
- if you release the Redis lock too early, another request can enter while the first transaction has not committed yet

So with Redis, the safest shape is:

1. acquire Redis lock
2. run the DB work inside `TransactionTemplate`
3. unlock Redis **after** the transaction finishes

That is the cleanest way to avoid the timing bug.

### Draft design

I would introduce a small lock service and call it from `GroupMembershipService`.

#### 1. Redis lock service

```java
package com.hello.chatapp.service;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Service
public class GroupMutationRedisLockService {

    private static final String LOCK_KEY_PREFIX = "group:mutation:lock:";
    private static final Duration LOCK_TTL = Duration.ofSeconds(30);
    private static final Duration ACQUIRE_TIMEOUT = Duration.ofSeconds(5);
    private static final long RETRY_SLEEP_MS = 50L;

    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            """
            if redis.call('get', KEYS[1]) == ARGV[1] then
                return redis.call('del', KEYS[1])
            end
            return 0
            """,
            Long.class);

    private final StringRedisTemplate redisTemplate;

    public GroupMutationRedisLockService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public <T> T withGroupLock(@NonNull Long groupId, @NonNull Supplier<T> action) {
        Objects.requireNonNull(groupId, "groupId must not be null");
        Objects.requireNonNull(action, "action must not be null");

        String key = lockKey(groupId);
        String token = UUID.randomUUID().toString();

        acquireLock(key, token);
        try {
            return action.get();
        } finally {
            releaseLock(key, token);
        }
    }

    public void withGroupLock(@NonNull Long groupId, @NonNull Runnable action) {
        withGroupLock(groupId, () -> {
            action.run();
            return null;
        });
    }

    private void acquireLock(String key, String token) {
        long deadlineNanos = System.nanoTime() + ACQUIRE_TIMEOUT.toNanos();

        while (System.nanoTime() < deadlineNanos) {
            Boolean acquired = redisTemplate.opsForValue().setIfAbsent(key, token, LOCK_TTL);
            if (Boolean.TRUE.equals(acquired)) {
                return;
            }

            try {
                TimeUnit.MILLISECONDS.sleep(RETRY_SLEEP_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for Redis group lock", e);
            }
        }

        throw new IllegalStateException("Could not acquire Redis group lock");
    }

    private void releaseLock(String key, String token) {
        redisTemplate.execute(UNLOCK_SCRIPT, List.of(key), token);
    }

    private String lockKey(Long groupId) {
        return LOCK_KEY_PREFIX + groupId;
    }
}
```

#### 2. Use `TransactionTemplate` in `GroupMembershipService`

This avoids the "unlock before commit" problem.

```java
package com.hello.chatapp.service;

import com.hello.chatapp.constant.GroupPermission;
import com.hello.chatapp.constant.GroupRole;
import com.hello.chatapp.constant.SystemEventType;
import com.hello.chatapp.dto.GroupMemberResponse;
import com.hello.chatapp.entity.Group;
import com.hello.chatapp.entity.GroupParticipant;
import com.hello.chatapp.entity.User;
import com.hello.chatapp.exception.BadRequestException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class GroupMembershipService {

    private final GroupMutationRedisLockService groupMutationRedisLockService;
    private final TransactionTemplate transactionTemplate;
    private final GroupAuthorizationService groupAuthorizationService;
    private final GroupParticipantRepository groupParticipantRepository;
    private final GroupRepository groupRepository;
    private final UserRepository userRepository;

    public GroupMembershipService(
            GroupMutationRedisLockService groupMutationRedisLockService,
            TransactionTemplate transactionTemplate,
            GroupAuthorizationService groupAuthorizationService,
            GroupParticipantRepository groupParticipantRepository,
            GroupRepository groupRepository,
            UserRepository userRepository) {
        this.groupMutationRedisLockService = groupMutationRedisLockService;
        this.transactionTemplate = transactionTemplate;
        this.groupAuthorizationService = groupAuthorizationService;
        this.groupParticipantRepository = groupParticipantRepository;
        this.groupRepository = groupRepository;
        this.userRepository = userRepository;
    }

    public GroupMemberResponse addMember(User actor, Long groupId, Long userId) {
        return groupMutationRedisLockService.withGroupLock(groupId, () ->
                transactionTemplate.execute(status -> addMemberTx(actor, groupId, userId)));
    }

    private GroupMemberResponse addMemberTx(User actor, Long groupId, Long userId) {
        Group group = loadActiveGroup(groupId);
        groupAuthorizationService.requireActivePermission(actor, groupId, GroupPermission.ADD_MEMBERS);

        User target = loadUser(userId);
        groupAuthorizationService.requireNotBanned(target, groupId);

        if (groupParticipantRepository.findByGroupIdAndUserId(groupId, userId).isPresent()) {
            throw new BadRequestException("User is already a member of this group");
        }

        GroupParticipant participant = new GroupParticipant(group, target);
        participant.setRole(GroupRole.MEMBER);
        GroupParticipant savedParticipant = groupParticipantRepository.save(participant);

        publishMembershipEvent(group, target, actor, SystemEventType.USER_JOINED, null);
        return GroupMemberResponse.fromParticipant(savedParticipant);
    }

    private Group loadActiveGroup(Long groupId) {
        Group group = groupRepository.findById(groupId)
                .orElseThrow(() -> new NotFoundException("Group with id " + groupId + " not found"));
        if (group.getArchivedAt() != null) {
            throw new BadRequestException("Group is archived");
        }
        return group;
    }

    private User loadUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User with id " + userId + " not found"));
    }
}
```

### What changes conceptually

With the current DB lock approach:

- `findByIdForUpdate(groupId)` is the shared mutex

With the Redis approach:

- `SET NX EX group:mutation:lock:{groupId}` is the shared mutex

So the protection becomes:

- Bob cannot enter `updateMemberRole(groupId=1)` while Alice is inside `addMember(groupId=1)`
- whichever gets the Redis lock first runs first
- the second request reads `group_participants` only after the first one committed

### Do we still need DB constraints?

Yes, absolutely.

Even if Redis is the main mutex, keep DB-level protection for correctness:

- unique participant membership if you have it
- one leader per group partial unique index
- normal DB transaction boundaries

Redis lock should reduce races, but the database should still be the final source of truth.

### Recommendation

For this project, I would still prefer the current DB row lock unless you have a real reason to move away from it.

Why:

- the DB row lock is simpler
- it naturally ends with the DB transaction
- no lease expiry / unlock timing issues
- fewer moving parts than Redis distributed locking

I would choose Redis only if:

- you want the same lock to coordinate work outside the DB too
- DB lock contention becomes a real bottleneck
- you are already committed to distributed app-level locking patterns

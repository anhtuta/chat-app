# Message Moderation vs Membership Auth Race

## Current Problem

`MessageModerationService.editMessage` / `deleteMessage` call `requireCanEditMessage` / `requireCanDeleteMessage`, then later save the message in the same transaction. A concurrent kick, ban, leave, or role demotion can remove or weaken the actor’s group membership **after** that check and **before** (or interleaved with) the message write.

Result: a user who is no longer allowed to moderate can still complete an edit/delete that was authorized on a stale membership/role snapshot.

This is check-then-act (TOCTOU) across **two resources**: `group_participants` (auth) and `messages` (write).

Related code:

- `MessageModerationService`
- `GroupAuthorizationService.requireCanEditMessage` / `requireCanDeleteMessage`
- Membership mutations in `GroupMembershipService` (kick, ban, leave, role update)

## Not the same as doc 19

| Doc                                                                                          | Race                                                             | Resource                               | Typical fix                                            |
| -------------------------------------------------------------------------------------------- | ---------------------------------------------------------------- | -------------------------------------- | ------------------------------------------------------ |
| [19_MESSAGE_MODERATION_OPTIMISTIC_LOCKING.md](./19_MESSAGE_MODERATION_OPTIMISTIC_LOCKING.md) | Two moderations on the **same message** (edit/edit, edit/delete) | `messages` row                         | `@Version` / conditional update on the message         |
| **This doc**                                                                                 | Moderation vs **membership/role** change                         | `group_participants` (+ message write) | Lock participant (or re-check/CAS auth with the write) |

`@Version` on `Message` does **not** fix this: the loser’s membership can still change without touching the message version.

## Possible Solutions

### 1. Lock participant row across auth + moderation write

- How it works: `SELECT … FOR UPDATE` on the actor’s `group_participants` row (when group message) before auth; membership mutations take the same lock before delete/role update.
- Pros: Same pattern as [21_LAST_MEMBER_LEAVE_CONCURRENT_JOIN.md](./21_LAST_MEMBER_LEAVE_CONCURRENT_JOIN.md); clear serialization.
- Cons: Must lock on every membership path that can revoke edit/delete rights; public messages have no participant row.
- Recommendation for our problem: **Yes** (strong candidate).

### 2. Re-validate membership/role immediately before save (or in a conditional UPDATE)

- How it works: After building the edit, re-run auth or `UPDATE … WHERE` only if actor still has membership/role.
- Pros: No long-held lock if the second check is cheap.
- Cons: Easy to miss a path; two checks can still race unless the final write is conditional on auth state.
- Recommendation: Acceptable if paired with an atomic condition; alone it only shrinks the window.

### 3. Ignore (status quo)

- Pros: Rare in practice (kick + edit same instant).
- Cons: Permission revocation is not enforceable at write time.
- Recommendation: **No** long-term.

## Recommendation

Treat as a **separate** follow-up from message `@Version` (doc 19). Prefer locking (or otherwise serializing) the participant lifecycle with moderation auth for group messages, plus concurrency tests: edit/delete racing with kick, ban, and demote.

## Implementation details

(Planned — not implemented yet.)

## Lesson (look back here)

Authorization at the start of a TX is not enough if another TX can revoke that authorization on a **different table** before you write. Lock or CAS the auth source of truth together with the privileged write — don’t confuse that with optimistic locking on the message row alone.

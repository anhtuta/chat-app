# Message Moderation vs Membership Auth Race

Severity: 🟡 Minor

## Current Problem

`MessageModerationService.editMessage` / `deleteMessage` call `requireCanEditMessage` / `requireCanDeleteMessage`, then later save the message in the same transaction. A concurrent kick, ban, leave, or role demotion can remove or weaken the actor’s group membership **after** that check and **before** (or interleaved with) the message write.

Result: a user who is no longer allowed to moderate can still complete an edit/delete that was authorized on a stale membership/role snapshot.

This is check-then-act (TOCTOU) across **two resources**: `group_participants` (auth) and `messages` (write).

Related code:

- `MessageModerationService.editMessage` / `deleteMessage` (`requireCan*` then `messageRepository.save`)
- `GroupAuthorizationService.requireCanEditMessage` / `requireCanDeleteMessage`
- Membership mutations in `GroupMembershipService` (kick, ban, leave, role update)

Giải thích đơn giản 1 case:

- User thực hiện sửa/xoá message trong nhóm
- `editMessage` / `deleteMessage` kiểm tra quyền trước khi lưu message
- Ngay lúc này, Co-Leader kick/ban user đó
- `editMessage` / `deleteMessage` tiếp tục lưu message
- Kết quả: user vẫn có thể sửa/xoá message sau khi bị kick/ban

Thực ra cái lỗi này cũng ko nghiêm trọng lắm, nếu xảy ra cũng ko gây thiệt hại gì!

### Examples (status quo — not fixed)

Role rules from [15_GROUP_ROLES_AND_PERMISSIONS.md](./15_GROUP_ROLES_AND_PERMISSIONS.md): nobody can kick/ban the `LEADER` via manage-target APIs. Own-message races use a `MEMBER`; moderate-anyone races use `CO_LEADER` (`EDIT_ANY_TEXT_MESSAGE` / `DELETE_ANY_MESSAGE`). Public messages are owner-only and have **no** `group_participants` row — this race does not apply there.

`editMessage` / `deleteMessage` today: load message (no lock) → `requireCanEditMessage` / `requireCanDeleteMessage` (unlocked membership/role read) → mutate + `save`. Kick/ban/leave/demote take the group lifecycle lock (doc 24) but **moderation does not share that lock**, so the two TXs interleave.

#### 1. Kick member, they still edit their own text

Alice is `MEMBER` (may edit her own group text). Bob is `LEADER` (can kick Alice).

1. Alice starts `PATCH /api/messages/{id}` on a text message she sent in the group.
2. `requireCanEditMessage` sees she is the owner and `requireMember` succeeds.
3. Bob’s concurrent kick deletes Alice’s `group_participants` row and commits.
4. Alice’s request writes `message_edit_history` and saves new `content`.

**Broken outcome:** A kicked member still edits a group message.

#### 2. Ban member, they still delete their own message

Alice is `MEMBER` (may delete her own group message, text or media). Bob is `LEADER` / `CO_LEADER` (can ban).

1. Alice starts `DELETE /api/messages/{id}` on her message.
2. `requireCanDeleteMessage` → owner path → `requireMember` succeeds (not banned yet).
3. Bob’s concurrent ban inserts a ban row and removes membership; commits.
4. Alice’s request sets `deleted_at` / `deleted_by` and saves.

**Broken outcome:** A banned user still soft-deletes a group message.

#### 3. Demote co-leader, they still edit someone else’s text

Alice is `CO_LEADER` (`EDIT_ANY_TEXT_MESSAGE`). Charlie sent a text message. Bob is `LEADER` (can demote Alice to `MEMBER`).

1. Alice starts `PATCH /api/messages/{id}` on Charlie’s message.
2. `requireCanEditMessage` takes the moderator path; `EDIT_ANY_TEXT_MESSAGE` passes.
3. Bob demotes Alice to `MEMBER` and commits (`MEMBER` cannot edit others’ messages).
4. Alice’s request still saves Charlie’s new content and an edit-history row attributed to Alice.

**Broken outcome:** A former co-leader still moderates another member’s text.

#### 4. Kick co-leader, they still delete someone else’s message

Alice is `CO_LEADER` (`DELETE_ANY_MESSAGE`). Charlie’s message is still visible. Bob is `LEADER` (can kick Alice).

1. Alice starts `DELETE /api/messages/{id}` on Charlie’s message.
2. `requireCanDeleteMessage` passes `DELETE_ANY_MESSAGE` while she is still `CO_LEADER`.
3. Bob kicks Alice; her participant row is gone.
4. Alice’s request still soft-deletes Charlie’s message.

**Broken outcome:** A removed moderator still deletes another member’s message.

#### 5. Member leaves, then their in-flight edit commits

Alice is `MEMBER` editing her own text. She also hits leave on another tab.

1. Alice’s edit passes `requireMember`.
2. Alice’s leave commits (participant row deleted; last-member leave may also archive — doc 21).
3. The edit TX saves new content.

**Broken outcome:** A user who already left still mutates a group message.

These are TOCTOU:

- **time of check** = unlocked `requireCanEditMessage` / `requireCanDeleteMessage`
- **time of use** = `messageRepository.save` after membership/role on another table already changed.

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

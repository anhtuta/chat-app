## Intro

`GroupSummaryUpdate` is the lightweight payload pushed over WebSocket so each member can refresh their group sidebar (and, for some events, open chat header / own role) without polling.

Clients subscribe to a personal topic:

`/topic/user.{username}.group-updates`

This document lists every production path that **constructs and publishes** a `GroupSummaryUpdate`, with an example JSON body for each.

Related code:

- DTO: `chat-app-backend/src/main/java/com/hello/chatapp/dto/GroupSummaryUpdate.java`
- Fan-out: `GroupSummaryUpdatePublisher`
- Chat send: `WebSocketController.pushGroupSummaryUpdate`
- Membership: `GroupMembershipService` + `GroupMembershipRealtimePublisher`
- Profile: `GroupService.updateGroup` + `GroupProfileRealtimePublisher`

## Delivery

There are two delivery modes:

| Mode                       | API                     | When                                                                    | Debounce                                                                   |
| -------------------------- | ----------------------- | ----------------------------------------------------------------------- | -------------------------------------------------------------------------- |
| Fan-out to current members | `publishToGroupMembers` | Chat text, membership system events, group name/description             | Buffered ~3s per `groupId`; only the latest update in the burst is flushed |
| Immediate personal         | `publishToUser`         | Kick/ban/leave `removed=true`; promote/demote/leadership access refresh | No debounce                                                                |

After kick/ban/leave the target is already deleted from `group_participants`, so member fan-out never includes them. That is why removal uses `publishToUser`.

`unreadCount` is **not** populated on any current publish path. Clients keep the value they already have (or increment locally).

`fromGroupResponse(...)` exists on the DTO but is **not called** anywhere.

## Payload field meanings

| Field                                        | Typical use                                                                                           |
| -------------------------------------------- | ----------------------------------------------------------------------------------------------------- |
| `groupId`                                    | Always set                                                                                            |
| `name`                                       | Membership/profile events so a newly added user can insert a sidebar row; also refreshes display name |
| `description`                                | Profile events only (`forGroupProfileEvent`)                                                          |
| `latestMessage`                              | Sidebar preview (chat text/media preview, or `SystemEventType.latestPreview()`)                       |
| `latestMessageSender`                        | Chat: sender username. System/profile: `"System"`                                                     |
| `latestMessageAt`                            | Timestamp of the triggering message                                                                   |
| `unreadCount`                                | Unused on WS today (`null`)                                                                           |
| `currentUserRole` / `currentUserPermissions` | Immediate personal access refresh only                                                                |
| `removed`                                    | `true` → drop this group from the recipient’s sidebar                                                 |

## Use cases

### 1. User sends a group text message

**Trigger:** STOMP `/app/group.send` → `WebSocketController` → `GroupSummaryUpdate.fromMessage`.

**Who receives it:** every current member (buffered fan-out).

**Factory:** `fromMessage` — no `name` / `description` / role fields.

```json
{
  "groupId": 20,
  "name": null,
  "description": null,
  "latestMessage": "hey everyone",
  "latestMessageSender": "alice",
  "latestMessageAt": "2026-08-27T22:19:56.811976",
  "unreadCount": null,
  "currentUserRole": null,
  "currentUserPermissions": null,
  "removed": false
}
```

Text longer than 255 characters is truncated in `latestMessage` (`MessageService.buildLatestMessagePreview`).

**Not this case:** content starting with `[SYSTEM] ` is not persisted and does **not** emit a `GroupSummaryUpdate`.

---

### 2. Members are added to the group

**Trigger:** `GroupMembershipService.addMembers` → `SystemEventType.USER_JOINED`.

**Who receives it:** current members **including the newly added users** (they are already in `group_participants` when the buffered flush runs).

**Factory:** `forSystemEvent`. Preview: `"Member joined"`.

```json
{
  "groupId": 20,
  "name": "Group 20",
  "description": null,
  "latestMessage": "Member joined",
  "latestMessageSender": "System",
  "latestMessageAt": "2026-08-27T22:19:56.811976",
  "unreadCount": null,
  "currentUserRole": null,
  "currentUserPermissions": null,
  "removed": false
}
```

The chat line itself (on `/topic/group.{groupId}`) carries structured `SYSTEM` metadata including added display names. The sidebar update only uses the short preview above.

---

### 3. User joins via join link

**Trigger:** `GroupMembershipService.joinByToken` when the user was not already a member → `USER_JOINED`.

**Who receives it:** all current members, including the joiner.

**Example:** same JSON as case 2 (`"Member joined"`). Already-members who reuse the link get **no** system message and **no** `GroupSummaryUpdate`.

---

### 4. Member is kicked

**Trigger:** `kickMember` → `USER_KICKED`.

Two payloads:

#### 4a. Remaining members (buffered fan-out)

Preview: `"Member removed"`.

```json
{
  "groupId": 20,
  "name": "Group 20",
  "description": null,
  "latestMessage": "Member removed",
  "latestMessageSender": "System",
  "latestMessageAt": "2026-08-27T22:19:56.811976",
  "unreadCount": null,
  "currentUserRole": null,
  "currentUserPermissions": null,
  "removed": false
}
```

#### 4b. Kicked user (immediate personal)

**Factory:** `removed`. Clients should drop the group from the sidebar.

```json
{
  "groupId": 20,
  "name": null,
  "description": null,
  "latestMessage": null,
  "latestMessageSender": null,
  "latestMessageAt": null,
  "unreadCount": null,
  "currentUserRole": null,
  "currentUserPermissions": null,
  "removed": true
}
```

---

### 5. User is banned

**Trigger:** `banMember` → `USER_BANNED`.

#### 5a. Remaining members (buffered)

Preview: `"Member banned"`. Same shape as 4a, with `latestMessage` `"Member banned"`.

```json
{
  "groupId": 20,
  "name": "Group 20",
  "description": null,
  "latestMessage": "Member banned",
  "latestMessageSender": "System",
  "latestMessageAt": "2026-08-27T22:19:56.811976",
  "unreadCount": null,
  "currentUserRole": null,
  "currentUserPermissions": null,
  "removed": false
}
```

#### 5b. Banned user who was still a member (immediate personal)

Same `removed: true` payload as 4b.

If the target was **already not** a participant (ban of a non-member), `removedUsername` is null: remaining members still get 5a, and nobody gets 5b.

---

### 6. User is unbanned

**Trigger:** `unbanMember` → `USER_UNBANNED`.

**Who receives it:** current members only. The unbanned user is not a member, so they do **not** get this update.

Preview: `"Member unbanned"`.

```json
{
  "groupId": 20,
  "name": "Group 20",
  "description": null,
  "latestMessage": "Member unbanned",
  "latestMessageSender": "System",
  "latestMessageAt": "2026-08-27T22:19:56.811976",
  "unreadCount": null,
  "currentUserRole": null,
  "currentUserPermissions": null,
  "removed": false
}
```

---

### 7. Member is promoted

**Trigger:** `updateMemberRole` when the new role has a **lower rank number** (e.g. MEMBER → ELDER) → `USER_PROMOTED`.

No-op if the role did not change.

#### 7a. All current members including the target (buffered)

Preview: `"Member promoted"`. Same shape as 4a with that preview. **No** role/permission fields on this fan-out.

```json
{
  "groupId": 20,
  "name": "Group 20",
  "description": null,
  "latestMessage": "Member promoted",
  "latestMessageSender": "System",
  "latestMessageAt": "2026-08-27T22:19:56.811976",
  "unreadCount": null,
  "currentUserRole": null,
  "currentUserPermissions": null,
  "removed": false
}
```

#### 7b. Promoted user only (immediate personal)

**Factory:** `forSystemEventWithAccess`. Role/permissions are the **new** role.

Example: promoted to `CO_LEADER`.

```json
{
  "groupId": 20,
  "name": "Group 20",
  "description": null,
  "latestMessage": "Member promoted",
  "latestMessageSender": "System",
  "latestMessageAt": "2026-08-27T22:19:56.811976",
  "unreadCount": null,
  "currentUserRole": "CO_LEADER",
  "currentUserPermissions": [
    "READ_MESSAGES",
    "SEND_MESSAGES",
    "CREATE_JOIN_LINK",
    "ADD_MEMBERS",
    "KICK_MEMBERS",
    "BAN_MEMBERS",
    "UNBAN_MEMBERS",
    "MANAGE_ROLES",
    "MANAGE_GROUP_DETAILS",
    "EDIT_ANY_TEXT_MESSAGE",
    "DELETE_ANY_MESSAGE"
  ],
  "removed": false
}
```

The target may later also receive 7a from the buffered flush. Frontend merge should not double-count unread when the same `latestMessageAt` arrives twice.

---

### 8. Member is demoted

**Trigger:** `updateMemberRole` when the new role has a **higher rank number** (e.g. CO_LEADER → MEMBER) → `USER_DEMOTED`.

#### 8a. All current members (buffered)

Same as 7a with `latestMessage`: `"Member demoted"`.

#### 8b. Demoted user only (immediate personal)

Example: demoted to `MEMBER`.

```json
{
  "groupId": 20,
  "name": "Group 20",
  "description": null,
  "latestMessage": "Member demoted",
  "latestMessageSender": "System",
  "latestMessageAt": "2026-08-27T22:19:56.811976",
  "unreadCount": null,
  "currentUserRole": "MEMBER",
  "currentUserPermissions": ["READ_MESSAGES", "SEND_MESSAGES"],
  "removed": false
}
```

Assigning `LEADER` via this API is rejected; use leadership transfer.

---

### 9. Leadership is transferred

**Trigger:** `transferLeadership` → `LEADERSHIP_TRANSFERRED`.

#### 9a. All current members (buffered)

```json
{
  "groupId": 20,
  "name": "Group 20",
  "description": null,
  "latestMessage": "Leadership transferred",
  "latestMessageSender": "System",
  "latestMessageAt": "2026-08-27T22:19:56.811976",
  "unreadCount": null,
  "currentUserRole": null,
  "currentUserPermissions": null,
  "removed": false
}
```

#### 9b. Former leader (immediate personal)

`currentUserRole`: `MEMBER` plus member permissions (same permission list as 8b).

```json
{
  "groupId": 20,
  "name": "Group 20",
  "description": null,
  "latestMessage": "Leadership transferred",
  "latestMessageSender": "System",
  "latestMessageAt": "2026-08-27T22:19:56.811976",
  "unreadCount": null,
  "currentUserRole": "MEMBER",
  "currentUserPermissions": ["READ_MESSAGES", "SEND_MESSAGES"],
  "removed": false
}
```

#### 9c. New leader (immediate personal)

`currentUserRole`: `LEADER` plus **all** `GroupPermission` values.

```json
{
  "groupId": 20,
  "name": "Group 20",
  "description": null,
  "latestMessage": "Leadership transferred",
  "latestMessageSender": "System",
  "latestMessageAt": "2026-08-27T22:19:56.811976",
  "unreadCount": null,
  "currentUserRole": "LEADER",
  "currentUserPermissions": [
    "READ_MESSAGES",
    "SEND_MESSAGES",
    "CREATE_JOIN_LINK",
    "ADD_MEMBERS",
    "KICK_MEMBERS",
    "BAN_MEMBERS",
    "UNBAN_MEMBERS",
    "MANAGE_ROLES",
    "MANAGE_GROUP_DETAILS",
    "EDIT_ANY_TEXT_MESSAGE",
    "DELETE_ANY_MESSAGE",
    "TRANSFER_LEADERSHIP"
  ],
  "removed": false
}
```

---

### 10. Member leaves the group

**Trigger:** `leaveGroup` → `USER_LEFT`.

#### 10a. Remaining members (buffered)

```json
{
  "groupId": 20,
  "name": "Group 20",
  "description": null,
  "latestMessage": "Member left",
  "latestMessageSender": "System",
  "latestMessageAt": "2026-08-27T22:19:56.811976",
  "unreadCount": null,
  "currentUserRole": null,
  "currentUserPermissions": null,
  "removed": false
}
```

#### 10b. Leaving user (immediate personal)

Same `removed: true` payload as 4b.

If this user was the **last member**, the group is archived and a second membership publish runs (case 11). The leaver is already gone from `group_participants`, so they only get 10b, not the archive sidebar preview.

---

### 11. Last member leaves (group archived)

**Trigger:** `leaveGroup` when `memberCount <= 1` → second event `GROUP_ARCHIVED`.

**Who receives it:** remaining members at flush time. After last-member leave that set is empty, so this fan-out usually has **no subscribers**. There is no `removed` personal payload for archive (the leaver already got 10b).

If it were delivered, the body would be:

```json
{
  "groupId": 20,
  "name": "Group 20",
  "description": null,
  "latestMessage": "Group archived",
  "latestMessageSender": "System",
  "latestMessageAt": "2026-08-27T22:20:01.000000",
  "unreadCount": null,
  "currentUserRole": null,
  "currentUserPermissions": null,
  "removed": false
}
```

---

### 12. Group name is updated

**Trigger:** `GroupService.updateGroup` when `name` actually changed → `GROUP_NAME_UPDATED`.

**Who receives it:** all current members (buffered).

**Factory:** `forGroupProfileEvent` — includes current `name` **and** `description` so open headers can refresh.

Preview: `"Group name updated"`.

```json
{
  "groupId": 20,
  "name": "New group name",
  "description": "Existing description or empty string",
  "latestMessage": "Group name updated",
  "latestMessageSender": "System",
  "latestMessageAt": "2026-08-27T22:19:56.811976",
  "unreadCount": null,
  "currentUserRole": null,
  "currentUserPermissions": null,
  "removed": false
}
```

---

### 13. Group description is updated

**Trigger:** `GroupService.updateGroup` when `description` actually changed → `GROUP_DESCRIPTION_UPDATED`.

**Who receives it:** all current members (buffered).

Preview: `"Group description updated"`.

```json
{
  "groupId": 20,
  "name": "Group 20",
  "description": "New desc for G20, haha",
  "latestMessage": "Group description updated",
  "latestMessageSender": "System",
  "latestMessageAt": "2026-08-27T22:19:56.811976",
  "unreadCount": null,
  "currentUserRole": null,
  "currentUserPermissions": null,
  "removed": false
}
```

If one PATCH changes **both** name and description, the backend records **two** system messages and publishes **two** profile updates (name first, then description). The buffered publisher keeps only the latest of those for that 3s window, so members may only see the description event in the sidebar if both happen in the same burst.

`maxMembers` changes do **not** emit a `GroupSummaryUpdate` (there is a TODO in `GroupService` about a system message).

---

## Events that do **not** publish `GroupSummaryUpdate`

These are easy to confuse with the cases above:

| Action                                                | What happens instead                                                                                    |
| ----------------------------------------------------- | ------------------------------------------------------------------------------------------------------- |
| Create group                                          | HTTP `GroupResponse` only                                                                               |
| Group media message (image/video/audio/file complete) | Full `MessageResponse` on `/topic/group.{id}` only; no sidebar summary WS                               |
| Edit or delete a group message                        | DB latest-message columns may update; **explicitly no** `GroupSummaryUpdate` (`MessageService` javadoc) |
| Public chat send / join / leave                       | `/topic/public` `MessageResponse` only                                                                  |
| Join-link create/revoke                               | HTTP only                                                                                               |
| `maxMembers`-only group update                        | HTTP `GroupResponse` only                                                                               |
| `GroupSummaryUpdate.fromGroupResponse`                | Factory exists; unused                                                                                  |

`fromMessage` **would** produce media previews (`"Photo"`, `"Photos"`, `"Video"`, `"Audio"`, or a file name) if chat-send used it for media. Media persistence goes through `MediaUploadSessionService`, which does not call `GroupSummaryUpdatePublisher`.

## High level Architecture/Design

```text
Chat text
  WebSocketController
    -> GroupSummaryUpdate.fromMessage
    -> publishToGroupMembers (3s buffer)
    -> /topic/user.{eachMember}.group-updates

Membership (join/add/kick/ban/unban/leave/archive)
  GroupMembershipService.publishMembershipEvent
    -> GroupMembershipRealtimePublisher (after commit)
       -> SYSTEM MessageResponse on /topic/group.{id}
       -> forSystemEvent + publishToGroupMembers
       -> optional publishToUser(removed) and/or forSystemEventWithAccess

Role / leadership
  Same as membership, plus Map of personal forSystemEventWithAccess
  (removed always overwrites that map entry for the same username)

Profile name / description
  GroupService.updateGroup
    -> GroupProfileRealtimePublisher (after commit)
       -> SYSTEM MessageResponse on /topic/group.{id}
       -> forGroupProfileEvent + publishToGroupMembers
```

## Implementation details

Already implemented. This file is a catalog of live publish cases, not a new feature plan.

Permission lists in examples match `GroupAuthorizationService.permissionsForRole` at the time of writing. If the matrix changes, update the promote / demote / leadership examples here.

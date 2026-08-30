## Intro

Yes: **`MessageResponse` is the chat-line payload sent over WebSocket** (and also returned from HTTP history / media complete / edit-delete). Subscribers on a chat topic receive this DTO, not `GroupSummaryUpdate`.

`GroupSummaryUpdate` is a separate, smaller payload on `/topic/user.{username}.group-updates` for the **sidebar**. See [38_GROUP_SUMMARY_UPDATE.md](./38_GROUP_SUMMARY_UPDATE.md). After a group event, clients often get **both**: a `MessageResponse` on the group topic (open chat) and a `GroupSummaryUpdate` on the personal topic (sidebar).

This document lists every production path that **publishes `MessageResponse` over WebSocket**, with an example JSON body.

Related code:

- DTO: `chat-app-backend/src/main/java/com/hello/chatapp/dto/MessageResponse.java`
- Storage-aware mapper (media URLs): `MessageResponseMapper`
- Delivery: `RealtimeMessageDeliveryService` (`convertAndSend` + RabbitMQ)
- Chat send: `WebSocketController`
- Public connect/disconnect: `WebSocketSecurityChannelInterceptor`, `WebSocketEventListener`
- Membership / profile SYSTEM lines: `GroupMembershipRealtimePublisher`, `GroupProfileRealtimePublisher`
- Media: `MediaUploadSessionService`, `AsyncMediaProcessingService`

## Delivery

| Topic                    | Who subscribes                  | Typical payload                                                  |
| ------------------------ | ------------------------------- | ---------------------------------------------------------------- |
| `/topic/public`          | Anyone connected to public chat | Public text, legacy `[SYSTEM] ` connect/disconnect, public media |
| `/topic/group.{groupId}` | Members with `READ_MESSAGES`    | Group text, structured `SYSTEM` events, group media              |

There is **no debounce** on `MessageResponse` (unlike group-summary fan-out). Each publish is delivered immediately after the surrounding DB transaction commits (membership/profile/media complete use `AfterCommit`).

Two builders:

| Builder                            | Used for WS                                           | Attachment URLs                                          |
| ---------------------------------- | ----------------------------------------------------- | -------------------------------------------------------- |
| `MessageResponse.fromMessage`      | Text, legacy `[SYSTEM] `, membership/profile `SYSTEM` | `fromEntity` only — **no** `contentUrl` / thumbnail URLs |
| `MessageResponseMapper.toResponse` | Media complete + media processing republish           | Adds storage read URLs                                   |

Jackson typically includes `null` fields. `attachments` is an empty list `[]` when there are none (not `null`).

## Payload field meanings

| Field                     | Typical use                                                                                                         |
| ------------------------- | ------------------------------------------------------------------------------------------------------------------- |
| `id`                      | Persisted message id. **`null`** for ephemeral `[SYSTEM] ` connect/disconnect (not saved)                           |
| `user`                    | Author for text/media. For `SYSTEM`, the **subject** (who joined, was kicked, …)                                    |
| `groupId`                 | Group chat id, or `null` for public                                                                                 |
| `messageType`             | `TEXT`, `IMAGE`, `VIDEO`, `AUDIO`, `FILE`, `SYSTEM`                                                                 |
| `content`                 | Text body; for structured `SYSTEM`, the **enum name** (`USER_JOINED`, …). `null` if deleted                         |
| `systemEventType`         | Parsed from `content` when `messageType` is `SYSTEM`; else `null`                                                   |
| `systemEventActor`        | Who performed a `SYSTEM` event (`messages.updated_by`)                                                              |
| `systemEventPayload`      | Extra JSON; batch add uses `subjectNames`. `null` otherwise                                                         |
| `updatedBy` / `updatedAt` | Last **text edit**. For `SYSTEM`, `updatedBy` is the same user as `systemEventActor`; `updatedAt` is usually `null` |
| `deletedBy` / `deletedAt` | Soft-delete. WS paths below do **not** currently republish deletes                                                  |
| `attachments`             | Media rows; empty for text/`SYSTEM`                                                                                 |
| `timestamp`               | Message time                                                                                                        |

Nested `user` / `systemEventActor` / `updatedBy` / `deletedBy` are `UserResponse`: `id`, `username`, `fullname`, `createdAt`.

Clients render structured `SYSTEM` copy from `systemEventType` + `user` + `systemEventActor` + `systemEventPayload` (not from a human `content` string). Sidebar previews use `SystemEventType.latestPreview()` on `GroupSummaryUpdate`, not this DTO.

## Use cases

Shared user blobs in examples:

```json
{
  "id": 1,
  "username": "alice",
  "fullname": "Alice",
  "createdAt": "2026-01-01T00:00:00"
}
```

```json
{
  "id": 2,
  "username": "bob",
  "fullname": "Bob",
  "createdAt": "2026-01-02T00:00:00"
}
```

---

### 1. User sends a public text message

**Trigger:** STOMP `/app/chat.send` → `WebSocketController.sendPublicMessage` (persisted).

**Topic:** `/topic/public` (`@SendTo` plus RabbitMQ).

**Mapper:** `fromMessage`.

```json
{
  "id": 101,
  "user": {
    "id": 1,
    "username": "alice",
    "fullname": "Alice",
    "createdAt": "2026-01-01T00:00:00"
  },
  "groupId": null,
  "messageType": "TEXT",
  "content": "hello public",
  "systemEventType": null,
  "systemEventActor": null,
  "systemEventPayload": null,
  "updatedBy": null,
  "updatedAt": null,
  "deletedBy": null,
  "deletedAt": null,
  "attachments": [],
  "timestamp": "2026-08-28T11:00:00.123456"
}
```

---

### 2. User connects (legacy public system line)

**Trigger:** STOMP `CONNECT` → `WebSocketSecurityChannelInterceptor.handleConnect`.

**Topic:** `/topic/public`.

**Not persisted.** Constructor sets `messageType` to `TEXT` (not `SYSTEM`). `id` is `null`. `systemEventType` stays `null` because type is not `SYSTEM`.

```json
{
  "id": null,
  "user": {
    "id": 1,
    "username": "alice",
    "fullname": "Alice",
    "createdAt": "2026-01-01T00:00:00"
  },
  "groupId": null,
  "messageType": "TEXT",
  "content": "[SYSTEM] alice connected",
  "systemEventType": null,
  "systemEventActor": null,
  "systemEventPayload": null,
  "updatedBy": null,
  "updatedAt": null,
  "deletedBy": null,
  "deletedAt": null,
  "attachments": [],
  "timestamp": "2026-08-28T11:00:01.000000"
}
```

The React client still treats `content` starting with `[SYSTEM] ` as a legacy system line.

---

### 3. User disconnects (legacy public system line)

**Trigger:** `WebSocketEventListener.handleWebSocketDisconnectListener`.

**Topic:** `/topic/public`. Same shape as case 2, with `content`:

```json
"[SYSTEM] alice disconnected"
```

---

### 4. Client sends a `[SYSTEM] ` prefixed public chat payload

**Trigger:** `/app/chat.send` when `content` starts with `[SYSTEM] `.

**Not persisted** (same as connect). Same JSON shape as case 2, with whatever string the client sent. The current React app does not send this for normal chat; the controller still supports it.

---

### 5. User sends a group text message

**Trigger:** STOMP `/app/group.send` → `WebSocketController.sendGroupMessage` (persisted).

**Topic:** `/topic/group.20`.

Also publishes a buffered `GroupSummaryUpdate` (doc 38, case 1).

```json
{
  "id": 201,
  "user": {
    "id": 1,
    "username": "alice",
    "fullname": "Alice",
    "createdAt": "2026-01-01T00:00:00"
  },
  "groupId": 20,
  "messageType": "TEXT",
  "content": "hey everyone",
  "systemEventType": null,
  "systemEventActor": null,
  "systemEventPayload": null,
  "updatedBy": null,
  "updatedAt": null,
  "deletedBy": null,
  "deletedAt": null,
  "attachments": [],
  "timestamp": "2026-08-28T11:05:00.123456"
}
```

---

### 6. Client sends a `[SYSTEM] ` prefixed group chat payload

**Trigger:** `/app/group.send` when `content` starts with `[SYSTEM] `.

**Not persisted.** No `GroupSummaryUpdate`. `groupId` is set from the request. `messageType` is still `TEXT`.

```json
{
  "id": null,
  "user": {
    "id": 1,
    "username": "alice",
    "fullname": "Alice",
    "createdAt": "2026-01-01T00:00:00"
  },
  "groupId": 20,
  "messageType": "TEXT",
  "content": "[SYSTEM] alice joined",
  "systemEventType": null,
  "systemEventActor": null,
  "systemEventPayload": null,
  "updatedBy": null,
  "updatedAt": null,
  "deletedBy": null,
  "deletedAt": null,
  "attachments": [],
  "timestamp": "2026-08-28T11:05:01.000000"
}
```

Membership join/leave in the product UI uses structured `SYSTEM` (cases 7–17), not this prefix.

---

## Structured group `SYSTEM` events

All of the following:

- Persist a row with `messageType: SYSTEM`, `content` = `SystemEventType` name
- Publish **after commit** via `GroupMembershipRealtimePublisher` or `GroupProfileRealtimePublisher`
- Topic: `/topic/group.{groupId}`
- Mapper: `fromMessage`
- Also fan out a `GroupSummaryUpdate` (doc 38)

`user` = **subject**. `systemEventActor` / `updatedBy` = **actor**. `attachments` is always `[]`. `systemEventPayload` is `null` unless noted.

### 7. Members are added

**Trigger:** `addMembers` → `USER_JOINED` with `systemEventPayload.subjectNames` (fullname, else username).

`user` is the first added member (dummy `messages.user_id`; clients should prefer `subjectNames`).

```json
{
  "id": 301,
  "user": {
    "id": 2,
    "username": "bob",
    "fullname": "Bob",
    "createdAt": "2026-01-02T00:00:00"
  },
  "groupId": 20,
  "messageType": "SYSTEM",
  "content": "USER_JOINED",
  "systemEventType": "USER_JOINED",
  "systemEventActor": {
    "id": 1,
    "username": "alice",
    "fullname": "Alice",
    "createdAt": "2026-01-01T00:00:00"
  },
  "systemEventPayload": {
    "subjectNames": ["Bob", "Carol"]
  },
  "updatedBy": {
    "id": 1,
    "username": "alice",
    "fullname": "Alice",
    "createdAt": "2026-01-01T00:00:00"
  },
  "updatedAt": null,
  "deletedBy": null,
  "deletedAt": null,
  "attachments": [],
  "timestamp": "2026-08-28T11:10:00.000000"
}
```

---

### 8. User joins via join link

**Trigger:** `joinByToken` for a **new** member → `USER_JOINED`.

Subject and actor are both the joiner. No `subjectNames`. Reusing the link as an existing member publishes nothing.

```json
{
  "id": 302,
  "user": {
    "id": 2,
    "username": "bob",
    "fullname": "Bob",
    "createdAt": "2026-01-02T00:00:00"
  },
  "groupId": 20,
  "messageType": "SYSTEM",
  "content": "USER_JOINED",
  "systemEventType": "USER_JOINED",
  "systemEventActor": {
    "id": 2,
    "username": "bob",
    "fullname": "Bob",
    "createdAt": "2026-01-02T00:00:00"
  },
  "systemEventPayload": null,
  "updatedBy": {
    "id": 2,
    "username": "bob",
    "fullname": "Bob",
    "createdAt": "2026-01-02T00:00:00"
  },
  "updatedAt": null,
  "deletedBy": null,
  "deletedAt": null,
  "attachments": [],
  "timestamp": "2026-08-28T11:11:00.000000"
}
```

---

### 9. Member is kicked

**Trigger:** `kickMember` → `USER_KICKED`.

Subject = kicked user. Actor = moderator. Remaining members receive this on the group topic; the kicked user is already removed from the group and typically is not subscribed.

```json
{
  "id": 303,
  "user": {
    "id": 2,
    "username": "bob",
    "fullname": "Bob",
    "createdAt": "2026-01-02T00:00:00"
  },
  "groupId": 20,
  "messageType": "SYSTEM",
  "content": "USER_KICKED",
  "systemEventType": "USER_KICKED",
  "systemEventActor": {
    "id": 1,
    "username": "alice",
    "fullname": "Alice",
    "createdAt": "2026-01-01T00:00:00"
  },
  "systemEventPayload": null,
  "updatedBy": {
    "id": 1,
    "username": "alice",
    "fullname": "Alice",
    "createdAt": "2026-01-01T00:00:00"
  },
  "updatedAt": null,
  "deletedBy": null,
  "deletedAt": null,
  "attachments": [],
  "timestamp": "2026-08-28T11:12:00.000000"
}
```

---

### 10. User is banned

**Trigger:** `banMember` → `USER_BANNED`.

Same envelope as case 9 with `content` / `systemEventType`: `"USER_BANNED"`. Subject = banned user (even if they were not a current member). Actor = moderator.

---

### 11. User is unbanned

**Trigger:** `unbanMember` → `USER_UNBANNED`.

Same envelope with `"USER_UNBANNED"`. Subject = unbanned user. Actor = moderator. Current members receive it; the unbanned user is not a member yet.

---

### 12. Member is promoted

**Trigger:** `updateMemberRole` when rank improves → `USER_PROMOTED`. No WS line if the role is unchanged.

Same envelope with `"USER_PROMOTED"`. Subject = target. Actor = moderator.

---

### 13. Member is demoted

**Trigger:** `updateMemberRole` when rank worsens → `USER_DEMOTED`.

Same envelope with `"USER_DEMOTED"`.

---

### 14. Leadership is transferred

**Trigger:** `transferLeadership` → `LEADERSHIP_TRANSFERRED`.

Subject = **new leader**. Actor = **former leader**.

```json
{
  "id": 310,
  "user": {
    "id": 2,
    "username": "bob",
    "fullname": "Bob",
    "createdAt": "2026-01-02T00:00:00"
  },
  "groupId": 20,
  "messageType": "SYSTEM",
  "content": "LEADERSHIP_TRANSFERRED",
  "systemEventType": "LEADERSHIP_TRANSFERRED",
  "systemEventActor": {
    "id": 1,
    "username": "alice",
    "fullname": "Alice",
    "createdAt": "2026-01-01T00:00:00"
  },
  "systemEventPayload": null,
  "updatedBy": {
    "id": 1,
    "username": "alice",
    "fullname": "Alice",
    "createdAt": "2026-01-01T00:00:00"
  },
  "updatedAt": null,
  "deletedBy": null,
  "deletedAt": null,
  "attachments": [],
  "timestamp": "2026-08-28T11:20:00.000000"
}
```

---

### 15. Member leaves

**Trigger:** `leaveGroup` → `USER_LEFT`.

Subject and actor are both the leaving user.

Same envelope with `"USER_LEFT"` and `user` / `systemEventActor` both the leaver.

---

### 16. Last member leaves (group archived)

**Trigger:** `leaveGroup` when that user was the last member → second persist `GROUP_ARCHIVED`.

Subject and actor are the leaver. Remaining subscribers: none (empty membership). The row is still written for history if the archived group is loaded later via HTTP.

Same envelope with `"GROUP_ARCHIVED"`.

---

### 17. Group name is updated

**Trigger:** `GroupService.updateGroup` when name changed → `GROUP_NAME_UPDATED` via `GroupProfileRealtimePublisher`.

Subject and actor are both the editor. New name is **not** on `MessageResponse`; clients refresh name from the companion `GroupSummaryUpdate` (doc 38, case 12).

```json
{
  "id": 401,
  "user": {
    "id": 1,
    "username": "alice",
    "fullname": "Alice",
    "createdAt": "2026-01-01T00:00:00"
  },
  "groupId": 20,
  "messageType": "SYSTEM",
  "content": "GROUP_NAME_UPDATED",
  "systemEventType": "GROUP_NAME_UPDATED",
  "systemEventActor": {
    "id": 1,
    "username": "alice",
    "fullname": "Alice",
    "createdAt": "2026-01-01T00:00:00"
  },
  "systemEventPayload": null,
  "updatedBy": {
    "id": 1,
    "username": "alice",
    "fullname": "Alice",
    "createdAt": "2026-01-01T00:00:00"
  },
  "updatedAt": null,
  "deletedBy": null,
  "deletedAt": null,
  "attachments": [],
  "timestamp": "2026-08-28T11:30:00.000000"
}
```

---

### 18. Group description is updated

**Trigger:** description actually changed → `GROUP_DESCRIPTION_UPDATED`.

Same envelope as case 17 with `"GROUP_DESCRIPTION_UPDATED"`. If one PATCH changes both name and description, **two** `MessageResponse` publishes happen (name first, then description). Unlike `GroupSummaryUpdate`, these are **not** coalesced.

`maxMembers`-only updates do **not** publish a `MessageResponse`.

---

## Media (mapper + URLs)

Complete goes through `MediaUploadSessionService.completeUploadSession` → `MessageResponseMapper.toResponse` → after-commit publish. Image/video then get extra publishes from `AsyncMediaProcessingService`.

There is **no** companion `GroupSummaryUpdate` for media today (doc 38).

### 19. Group image/video just completed (still processing)

**Topic:** `/topic/group.20`.

`content` is `null`. Status starts as `PROCESSING_PENDING` (image/video). Derived URLs are `null` until processing finishes (and the object exists).

```json
{
  "id": 501,
  "user": {
    "id": 1,
    "username": "alice",
    "fullname": "Alice",
    "createdAt": "2026-01-01T00:00:00"
  },
  "groupId": 20,
  "messageType": "IMAGE",
  "content": null,
  "systemEventType": null,
  "systemEventActor": null,
  "systemEventPayload": null,
  "updatedBy": null,
  "updatedAt": null,
  "deletedBy": null,
  "deletedAt": null,
  "attachments": [
    {
      "id": 9001,
      "attachmentOrder": 0,
      "originalFilename": "photo.png",
      "mimeType": "image/png",
      "sizeBytes": 12345,
      "status": "PROCESSING_PENDING",
      "scanStatus": "SCAN_PASSED",
      "width": null,
      "height": null,
      "durationMs": null,
      "contentUrl": "http://localhost:9000/chat-media/media/20/photo.png",
      "thumbnailUrl": null,
      "previewUrl": null,
      "transcodedUrl": null
    }
  ],
  "timestamp": "2026-08-28T11:40:00.000000"
}
```

Multi-image uses `messageType: "IMAGE"` and several attachments (`attachmentOrder` 0, 1, …).

Video uses `"VIDEO"`; later processing may fill `transcodedUrl` instead of `previewUrl`.

---

### 20. Group image/video processing in progress or ready (republish)

**Trigger:** `AsyncMediaProcessingService.publishUpdatedMessage`.

Same topic and `id`. Status becomes `PROCESSING_IN_PROGRESS`, then `MEDIA_READY`. Example when ready (placeholder derived keys; URLs only if the object exists):

```json
{
  "id": 501,
  "user": {
    "id": 1,
    "username": "alice",
    "fullname": "Alice",
    "createdAt": "2026-01-01T00:00:00"
  },
  "groupId": 20,
  "messageType": "IMAGE",
  "content": null,
  "systemEventType": null,
  "systemEventActor": null,
  "systemEventPayload": null,
  "updatedBy": null,
  "updatedAt": null,
  "deletedBy": null,
  "deletedAt": null,
  "attachments": [
    {
      "id": 9001,
      "attachmentOrder": 0,
      "originalFilename": "photo.png",
      "mimeType": "image/png",
      "sizeBytes": 12345,
      "status": "MEDIA_READY",
      "scanStatus": "SCAN_PASSED",
      "width": null,
      "height": null,
      "durationMs": null,
      "contentUrl": "http://localhost:9000/chat-media/media/20/photo.png",
      "thumbnailUrl": "http://localhost:9000/chat-media/media/20/photo.png.thumbnail.jpg",
      "previewUrl": "http://localhost:9000/chat-media/media/20/photo.png.preview",
      "transcodedUrl": null
    }
  ],
  "timestamp": "2026-08-28T11:40:00.000000"
}
```

On failure, `status` is `"PROCESSING_FAILED"` and derived URLs stay unset.

---

### 21. Group audio or file completed

**Topic:** `/topic/group.20`.

No async processing. Status is `MEDIA_READY` on the first publish.

Audio:

```json
{
  "id": 502,
  "user": {
    "id": 1,
    "username": "alice",
    "fullname": "Alice",
    "createdAt": "2026-01-01T00:00:00"
  },
  "groupId": 20,
  "messageType": "AUDIO",
  "content": null,
  "systemEventType": null,
  "systemEventActor": null,
  "systemEventPayload": null,
  "updatedBy": null,
  "updatedAt": null,
  "deletedBy": null,
  "deletedAt": null,
  "attachments": [
    {
      "id": 9002,
      "attachmentOrder": 0,
      "originalFilename": "voice.webm",
      "mimeType": "audio/webm",
      "sizeBytes": 8000,
      "status": "MEDIA_READY",
      "scanStatus": "SCAN_PASSED",
      "width": null,
      "height": null,
      "durationMs": null,
      "contentUrl": "http://localhost:9000/chat-media/media/20/voice.webm",
      "thumbnailUrl": null,
      "previewUrl": null,
      "transcodedUrl": null
    }
  ],
  "timestamp": "2026-08-28T11:41:00.000000"
}
```

File uses `"FILE"` and the original filename on the attachment (and in the sidebar preview if a `GroupSummaryUpdate` were sent).

---

### 22. Public media completed / processed

Same as cases 19–21 with **`groupId`: `null`**.

**Topic:** `/topic/public`.

Processing republishes also go to `/topic/public`.

---

## Events that return `MessageResponse` but do **not** publish it over WebSocket

| Action                                                   | What happens                                                                            |
| -------------------------------------------------------- | --------------------------------------------------------------------------------------- |
| `GET` public / group message history                     | HTTP list only (`MessageResponseMapper`)                                                |
| Edit message (`MessageModerationService`)                | HTTP body only; group latest-message column may refresh; **no** STOMP republish         |
| Delete message                                           | HTTP body with `deletedAt` / `content` null / empty attachments; **no** STOMP republish |
| Create group / join-link CRUD / `maxMembers`-only update | No `MessageResponse` WS                                                                 |
| Sidebar `GroupSummaryUpdate`                             | Different DTO and topic                                                                 |

Open chat windows will **not** see live edit/delete until they reload history (or until a WS republish is added).

## High level Architecture/Design

```text
Public text / legacy [SYSTEM] prefix
  /app/chat.send
    -> MessageResponse.fromMessage
    -> /topic/public  (@SendTo + RabbitMQ)

Connect / disconnect
  interceptor / SessionDisconnectEvent
    -> fromMessage (ephemeral TEXT, content "[SYSTEM] …")
    -> /topic/public

Group text / legacy [SYSTEM] prefix
  /app/group.send
    -> fromMessage
    -> /topic/group.{id}
    -> (persisted text only) GroupSummaryUpdate

Membership / profile SYSTEM
  recordGroupEvent
    -> after commit fromMessage
    -> /topic/group.{id}
    -> GroupSummaryUpdate

Media complete
  MessageResponseMapper.toResponse
    -> after commit /topic/public or /topic/group.{id}

Image/video processing
  AsyncMediaProcessingService
    -> mapper toResponse
    -> same topic, same message id, updated attachment status/URLs
```

## Implementation details

Already implemented. This file catalogs live WebSocket publishes of `MessageResponse`.

`contentUrl` host/path in examples is illustrative; real URLs come from the configured object-storage provider.

## Current Problem

Group history and live updates can both hold the same message id. `mergeMessagesById` used to apply `currentMessages` after the fetched page, so the in-memory copy always won. That was wrong when the fetched copy was newer, especially after an attachment status change.

## Examples (status quo — before the fix)

Alice sends a video in group 20.

1. The websocket delivers message `42` while processing is still pending. That copy is stored in `currentMessages`:

```json
{
  "id": 42,
  "attachments": [
    { "status": "PROCESSING_PENDING", "playbackUrl": null }
  ]
}
```

2. Processing finishes. `GET /api/messages/groups/20` returns the same id, now ready:

```json
{
  "id": 42,
  "attachments": [
    { "status": "MEDIA_READY", "playbackUrl": "https://cdn/.../video.mp4" }
  ]
}
```

3. `loadGroupMessages` called `mergeMessagesById(fetchedMessages, currentMessages)`. The stale websocket copy overwrote the fetched one. The video stayed stuck on processing until a full reload.

## Recommendation

Expose one server field, `freshnessKey`, and keep the duplicate with the later key. The key is the latest of `messages.timestamp`, `messages.updated_at`, `messages.deleted_at`, and each attachment's `message_media.updated_at`. It is not `Message.version` or `updatedAt` alone, because an attachment-only change must move it forward.

## Implementation details

- `MessageResponse` and `MessageResponseMapper` set `freshnessKey`.
- `mergeMessagesById` and `upsertMessage` keep the copy whose `freshnessKey` is later.
- If `freshnessKey` is missing, the client falls back to `timestamp`.

## Examples (after the fix)

Same send. Both copies now include the key.

Websocket copy, stored first:

```json
{
  "id": 42,
  "freshnessKey": "2026-09-25T11:00:00",
  "attachments": [
    { "status": "PROCESSING_PENDING", "playbackUrl": null }
  ]
}
```

Fetched copy, after processing updates the attachment at `11:00:01`:

```json
{
  "id": 42,
  "freshnessKey": "2026-09-25T11:00:01",
  "attachments": [
    { "status": "MEDIA_READY", "playbackUrl": "https://cdn/.../video.mp4" }
  ]
}
```

The merge compares `2026-09-25T11:00:00` and `2026-09-25T11:00:01`, keeps the fetched copy, and the video becomes playable.

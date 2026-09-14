# Flow when user uploads a video with media processing

The first steps are the same as [12_user-uploads-a-video-59mb.md](./12_user-uploads-a-video-59mb.md), include:

- FE calls API `/api/media/messages/prepare` to prepare the upload session
- FE calls API `api/media/messages/upload-sessions/9babe300-ca25-4ade-a44c-b1670a2d29ce/attachments/23ce07fe-7ab9-4da0-8bdd-6e3a5d2e86aa/parts` to request multipart part URLs
- FE uploads each part directly to object storage (MinIO/S3) via presigned URLs
- FE calls API `api/media/messages/upload-sessions/9babe300-ca25-4ade-a44c-b1670a2d29ce/complete` to complete the upload session

After that, each member in the group will receive following messages via websocket (they're the same for all members, include the sender) (both from chat-app-backend, not from the worker):

1. Message with status `PROCESSING_PENDING`, this message is sent immediately after the upload session is completed, and before the media processing is started

```json
{
  "id": 213634,
  "user": { "id": 1, "username": "anhtu", "fullname": "Tạ Anh Tú 95", "createdAt": "2025-12-11T06:39:48.717407" },
  "groupId": 5,
  "messageType": "VIDEO",
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
      "id": 84,
      "attachmentOrder": 0,
      "originalFilename": "sample-video-24mb.mov",
      "mimeType": "video/quicktime",
      "sizeBytes": 24798600,
      "status": "PROCESSING_PENDING",
      "scanStatus": "SCAN_PASSED",
      "width": null,
      "height": null,
      "durationMs": null,
      "contentUrl": "http://localhost:9000/chat-media/media/1/video/b9360bd2-3a78-445c-8b69-81b0e57845e7-sample-video-24mb.mov?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=minioadmin%2F20260912%2Fus-east-1%2Fs3%2Faws4_request&X-Amz-Date=20260912T093302Z&X-Amz-Expires=3600&X-Amz-SignedHeaders=host&X-Amz-Signature=5f446e3b087630f68574846a6e3b500c50611cb8aef951b8a6ebc4ec6a99c65f",
      "downloadUrl": "http://localhost:9000/chat-media/media/1/video/b9360bd2-3a78-445c-8b69-81b0e57845e7-sample-video-24mb.mov?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=minioadmin%2F20260912%2Fus-east-1%2Fs3%2Faws4_request&X-Amz-Date=20260912T093302Z&X-Amz-Expires=3600&X-Amz-SignedHeaders=host&X-Amz-Signature=5f446e3b087630f68574846a6e3b500c50611cb8aef951b8a6ebc4ec6a99c65f",
      "playbackUrl": "http://localhost:9000/chat-media/media/1/video/b9360bd2-3a78-445c-8b69-81b0e57845e7-sample-video-24mb.mov?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=minioadmin%2F20260912%2Fus-east-1%2Fs3%2Faws4_request&X-Amz-Date=20260912T093302Z&X-Amz-Expires=3600&X-Amz-SignedHeaders=host&X-Amz-Signature=5f446e3b087630f68574846a6e3b500c50611cb8aef951b8a6ebc4ec6a99c65f",
      "thumbnailUrl": null,
      "posterUrl": null,
      "previewUrl": null,
      "transcodedUrl": null,
      "videoSources": [
        {
          "url": "http://localhost:9000/chat-media/media/1/video/b9360bd2-3a78-445c-8b69-81b0e57845e7-sample-video-24mb.mov?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=minioadmin%2F20260912%2Fus-east-1%2Fs3%2Faws4_request&X-Amz-Date=20260912T093302Z&X-Amz-Expires=3600&X-Amz-SignedHeaders=host&X-Amz-Signature=5f446e3b087630f68574846a6e3b500c50611cb8aef951b8a6ebc4ec6a99c65f",
          "mimeType": "video/mp4",
          "width": null,
          "height": null,
          "sizeBytes": 24798600,
          "role": "CANONICAL"
        }
      ]
    }
  ],
  "timestamp": "2026-09-12T16:33:01.835767"
}
```

2. Message with status `MEDIA_READY`, this message is sent after the media processing is completed

```json
{
  "id": 213634,
  "user": { "id": 1, "username": "anhtu", "fullname": "Tạ Anh Tú 95", "createdAt": "2025-12-11T06:39:48.717407" },
  "groupId": 5,
  "messageType": "VIDEO",
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
      "id": 84,
      "attachmentOrder": 0,
      "originalFilename": "sample-video-24mb.mov",
      "mimeType": "video/mp4",
      "sizeBytes": 24799528,
      "status": "MEDIA_READY",
      "scanStatus": "SCAN_PASSED",
      "width": 3024,
      "height": 1964,
      "durationMs": 32167,
      "contentUrl": "http://localhost:9000/chat-media/media/1/video/b9360bd2-3a78-445c-8b69-81b0e57845e7-sample-video-24mb.transcoded.mp4?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=minioadmin%2F20260912%2Fus-east-1%2Fs3%2Faws4_request&X-Amz-Date=20260912T093308Z&X-Amz-Expires=3600&X-Amz-SignedHeaders=host&X-Amz-Signature=bb545a9806052b901f1d43196493b064fa81b81fe121743400cce508568abd73",
      "downloadUrl": "http://localhost:9000/chat-media/media/1/video/b9360bd2-3a78-445c-8b69-81b0e57845e7-sample-video-24mb.transcoded.mp4?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=minioadmin%2F20260912%2Fus-east-1%2Fs3%2Faws4_request&X-Amz-Date=20260912T093308Z&X-Amz-Expires=3600&X-Amz-SignedHeaders=host&X-Amz-Signature=bb545a9806052b901f1d43196493b064fa81b81fe121743400cce508568abd73",
      "playbackUrl": "http://localhost:9000/chat-media/media/1/video/b9360bd2-3a78-445c-8b69-81b0e57845e7-sample-video-24mb.transcoded.mp4?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=minioadmin%2F20260912%2Fus-east-1%2Fs3%2Faws4_request&X-Amz-Date=20260912T093308Z&X-Amz-Expires=3600&X-Amz-SignedHeaders=host&X-Amz-Signature=bb545a9806052b901f1d43196493b064fa81b81fe121743400cce508568abd73",
      "thumbnailUrl": "http://localhost:9000/chat-media/media/1/video/b9360bd2-3a78-445c-8b69-81b0e57845e7-sample-video-24mb.thumbnail.jpg?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=minioadmin%2F20260912%2Fus-east-1%2Fs3%2Faws4_request&X-Amz-Date=20260912T093308Z&X-Amz-Expires=3600&X-Amz-SignedHeaders=host&X-Amz-Signature=88a2183287bdf0f4376b49e4df0d3c7182d16cc65e71ee3f60b2ccb45925e1e2",
      "posterUrl": "http://localhost:9000/chat-media/media/1/video/b9360bd2-3a78-445c-8b69-81b0e57845e7-sample-video-24mb.thumbnail.jpg?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=minioadmin%2F20260912%2Fus-east-1%2Fs3%2Faws4_request&X-Amz-Date=20260912T093308Z&X-Amz-Expires=3600&X-Amz-SignedHeaders=host&X-Amz-Signature=88a2183287bdf0f4376b49e4df0d3c7182d16cc65e71ee3f60b2ccb45925e1e2",
      "previewUrl": null,
      "transcodedUrl": "http://localhost:9000/chat-media/media/1/video/b9360bd2-3a78-445c-8b69-81b0e57845e7-sample-video-24mb.transcoded.mp4?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=minioadmin%2F20260912%2Fus-east-1%2Fs3%2Faws4_request&X-Amz-Date=20260912T093308Z&X-Amz-Expires=3600&X-Amz-SignedHeaders=host&X-Amz-Signature=bb545a9806052b901f1d43196493b064fa81b81fe121743400cce508568abd73",
      "videoSources": [
        {
          "url": "http://localhost:9000/chat-media/media/1/video/b9360bd2-3a78-445c-8b69-81b0e57845e7-sample-video-24mb.transcoded.mp4?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=minioadmin%2F20260912%2Fus-east-1%2Fs3%2Faws4_request&X-Amz-Date=20260912T093308Z&X-Amz-Expires=3600&X-Amz-SignedHeaders=host&X-Amz-Signature=bb545a9806052b901f1d43196493b064fa81b81fe121743400cce508568abd73",
          "mimeType": "video/mp4",
          "width": 3024,
          "height": 1964,
          "sizeBytes": 24799528,
          "role": "CANONICAL"
        },
        {
          "url": "http://localhost:9000/chat-media/media/1/video/b9360bd2-3a78-445c-8b69-81b0e57845e7-sample-video-24mb.480p.mp4?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=minioadmin%2F20260912%2Fus-east-1%2Fs3%2Faws4_request&X-Amz-Date=20260912T093308Z&X-Amz-Expires=3600&X-Amz-SignedHeaders=host&X-Amz-Signature=79820eb4d2fe238470901c3767e8208485e6d16f0c595bbfca5c9b4f888d6e86",
          "mimeType": "video/mp4",
          "width": 740,
          "height": 480,
          "sizeBytes": 1564297,
          "role": "MOBILE"
        }
      ]
    }
  ],
  "timestamp": "2026-09-12T16:33:01.835767"
}
```

Sequence diagram for the processing path (after complete): [docs/diagram/29_01-video-processing-group-websocket.puml](../diagram/29_01-video-processing-group-websocket.puml).

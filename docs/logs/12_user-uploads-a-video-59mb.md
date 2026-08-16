# Flow

User clicks to Attach button

User selects a video `video-59mb.mp4`, size 59MB, at this point, no API call is made yet

User clicks to Send button, after this click, browser executes the following APIs:

1. Init the upload session with 1 attachment (omitted headers for brevity):

```sh
curl --url 'http://localhost:9010/api/media/messages/prepare' \
  -H 'Accept: */*' \
  -H 'Content-Type: application/json' \
  -b 'CHATAPP_SESSION=M2QwYzc5Y2ItNGFjNC00N2IyLTkwOTYtZWE3ZjA4ODIwOTU5' \
  -H 'Origin: http://localhost:9010' \
  -H 'Referer: http://localhost:9010/group/3' \
  --data-raw '{"chatScope":"GROUP","groupId":3,"messageType":"VIDEO","attachments":[{"filename":"video-59mb.mp4","mimeType":"video/mp4","sizeBytes":59453303}]}'
```

Response:

```json
{
  "uploadSessionId": "4a6e20b2-c10f-4431-9627-2337c52e340a",
  "messageType": "VIDEO",
  "chatScope": "GROUP",
  "expiresAt": "2026-08-08T16:55:28.439328",
  "retentionDays": 60,
  "limits": {
    "maxSizeBytes": 209715200,
    "maxAttachmentCount": 1
  },
  "attachments": [
    {
      "attachmentId": "2be57809-9acd-4c9c-a251-dda7cc38f295",
      "objectKey": "media/2/video/47e8594a-e324-4326-aecd-a48c57901089-video-59mb.mp4",
      "uploadStrategy": "MULTIPART",
      "presignedUrl": null,
      "multipartUploadId": null,
      "recommendedPartSize": 10485760,
      "completeBy": "2026-08-08T16:55:28.439328"
    }
  ]
}
```

As we can see, the response:

- Indicate this is a multipart upload session: `"uploadStrategy": "MULTIPART"`.
- Doesn't have a `presignedUrl` field, because it's a multipart upload session.
- Has a `recommendedPartSize` field, which is the recommended part size for each part in the multipart upload session (in this case, 10MB).
- Has a `attachmentId` field, which is the ID of the attachment in the multipart upload session. We store this ID in table `media_uploads`:
  ```json
  {
    "id": 84,
    "upload_id": "2be57809-9acd-4c9c-a251-dda7cc38f295",
    "user_id": 2,
    "chat_scope": "GROUP",
    "group_id": 3,
    "upload_session_id": "4a6e20b2-c10f-4431-9627-2337c52e340a",
    "requested_message_type": "VIDEO",
    "requested_filename": "video-59mb.mp4",
    "requested_size_bytes": 59453303,
    "requested_mime_type": "video/mp4",
    "storage_provider": "MINIO",
    "bucket": "chat-media",
    "object_key": "media/2/video/47e8594a-e324-4326-aecd-a48c57901089-video-59mb.mp4",
    "multipart_upload_id": "YjE1MzZmY2EtMTE4Zi00ZTg1LWEzZDYtODhlNDRiZTUwYzc4LjE2MzYyNjEwLTJiYzktNDhhNS05Njg4LTAwOGIwNzU1MGRhN3gxNzg2MTgyMDI4Njg4MDc0NzYy",
    "status": "UPLOAD_COMPLETED",
    "expires_at": "2026-08-08 16:55:28.439328",
    "created_at": "2026-08-08 16:40:28.491664",
    "updated_at": "2026-08-08 16:40:29.542866"
  }
  ```
- Note: that row above is copied after everything is completed. But at this point, `multipart_upload_id` is still `null`.

FE handles the logic:

- Since the size of the video is 59MB, and the recommended part size is 10MB, there will be 6 parts in the multipart upload session.
- FE slices the video into 6 parts, uses a batch size of 4 (`MULTIPART_PART_URL_BATCH_SIZE` in `ChatArea.js`), which means we have 2 batches
  - the first batch contains the first 4 parts (1, 2, 3, 4).
  - the second batch contains the last 2 parts (5, 6).

2. Browser requests the presigned URLs for the first 4 parts in the multipart upload session (first batch):

```sh
curl --url 'http://localhost:9010/api/media/messages/upload-sessions/4a6e20b2-c10f-4431-9627-2337c52e340a/attachments/2be57809-9acd-4c9c-a251-dda7cc38f295/parts' \
  -H 'Accept: */*' \
  -H 'Content-Type: application/json' \
  -b 'CHATAPP_SESSION=M2QwYzc5Y2ItNGFjNC00N2IyLTkwOTYtZWE3ZjA4ODIwOTU5' \
  -H 'Origin: http://localhost:9010' \
  -H 'Referer: http://localhost:9010/group/3' \
  --data-raw '{"partNumbers":[1,2,3,4]}'
```

Response (with 4 presigned URLs for the first 4 parts):

```json
{
  "multipartUploadId": "YjE1MzZmY2EtMTE4Zi00ZTg1LWEzZDYtODhlNDRiZTUwYzc4LjE2MzYyNjEwLTJiYzktNDhhNS05Njg4LTAwOGIwNzU1MGRhN3gxNzg2MTgyMDI4Njg4MDc0NzYy",
  "parts": [
    {
      "partNumber": 1,
      "presignedUrl": "http://localhost:9000/chat-media/media/2/video/47e8594a-e324-4326-aecd-a48c57901089-video-59mb.mp4?uploadId=YjE1MzZmY2EtMTE4Zi00ZTg1LWEzZDYtODhlNDRiZTUwYzc4LjE2MzYyNjEwLTJiYzktNDhhNS05Njg4LTAwOGIwNzU1MGRhN3gxNzg2MTgyMDI4Njg4MDc0NzYy&partNumber=1&X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=minioadmin%2F20260808%2Fus-east-1%2Fs3%2Faws4_request&X-Amz-Date=20260808T094028Z&X-Amz-Expires=900&X-Amz-SignedHeaders=host&X-Amz-Signature=9b799c169bf076aebd66e9b1d36572c9a4f8369531117fdb87df617548208303"
    },
    {
      "partNumber": 2,
      "presignedUrl": "http://localhost:9000/chat-media/media/2/video/47e8594a-e324-4326-aecd-a48c57901089-video-59mb.mp4?uploadId=YjE1MzZmY2EtMTE4Zi00ZTg1LWEzZDYtODhlNDRiZTUwYzc4LjE2MzYyNjEwLTJiYzktNDhhNS05Njg4LTAwOGIwNzU1MGRhN3gxNzg2MTgyMDI4Njg4MDc0NzYy&partNumber=2&X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=minioadmin%2F20260808%2Fus-east-1%2Fs3%2Faws4_request&X-Amz-Date=20260808T094028Z&X-Amz-Expires=900&X-Amz-SignedHeaders=host&X-Amz-Signature=3d7a5696d2245a7279bca14010fe3e5b97f497584dd85c7bd3318d2a39ed3f2a"
    },
    {
      "partNumber": 3,
      "presignedUrl": "http://localhost:9000/chat-media/media/2/video/47e8594a-e324-4326-aecd-a48c57901089-video-59mb.mp4?uploadId=YjE1MzZmY2EtMTE4Zi00ZTg1LWEzZDYtODhlNDRiZTUwYzc4LjE2MzYyNjEwLTJiYzktNDhhNS05Njg4LTAwOGIwNzU1MGRhN3gxNzg2MTgyMDI4Njg4MDc0NzYy&partNumber=3&X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=minioadmin%2F20260808%2Fus-east-1%2Fs3%2Faws4_request&X-Amz-Date=20260808T094028Z&X-Amz-Expires=900&X-Amz-SignedHeaders=host&X-Amz-Signature=277e4d3b91378b2b2203c7408272dbd91641b6d94676311f111493aa0cd07649"
    },
    {
      "partNumber": 4,
      "presignedUrl": "http://localhost:9000/chat-media/media/2/video/47e8594a-e324-4326-aecd-a48c57901089-video-59mb.mp4?uploadId=YjE1MzZmY2EtMTE4Zi00ZTg1LWEzZDYtODhlNDRiZTUwYzc4LjE2MzYyNjEwLTJiYzktNDhhNS05Njg4LTAwOGIwNzU1MGRhN3gxNzg2MTgyMDI4Njg4MDc0NzYy&partNumber=4&X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=minioadmin%2F20260808%2Fus-east-1%2Fs3%2Faws4_request&X-Amz-Date=20260808T094028Z&X-Amz-Expires=900&X-Amz-SignedHeaders=host&X-Amz-Signature=f7671fb34016d3f8bbed8d5a4ad02d0c2bf8e4deec34518fecf0128825f563ad"
    }
  ]
}
```

3. Browser uploads the video to the presigned URLs for the parts in the multipart upload session (directly to object storage):

First part:

```sh
curl --url 'http://localhost:9000/chat-media/media/2/video/47e8594a-e324-4326-aecd-a48c57901089-video-59mb.mp4?uploadId=YjE1MzZmY2EtMTE4Zi00ZTg1LWEzZDYtODhlNDRiZTUwYzc4LjE2MzYyNjEwLTJiYzktNDhhNS05Njg4LTAwOGIwNzU1MGRhN3gxNzg2MTgyMDI4Njg4MDc0NzYy&partNumber=1&X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=minioadmin%2F20260808%2Fus-east-1%2Fs3%2Faws4_request&X-Amz-Date=20260808T094028Z&X-Amz-Expires=900&X-Amz-SignedHeaders=host&X-Amz-Signature=9b799c169bf076aebd66e9b1d36572c9a4f8369531117fdb87df617548208303' \
  -X 'PUT' \
  -H 'Content-Type: video/mp4' \
  -H 'Origin: http://localhost:9010' \
  --data-raw $'\u0000\u0000\u0000 ...'
```

Do the same with the other 3 parts (can be in any order):

```sh
http://localhost:9000/chat-media/media/2/video/47e8594a-e324-4326-aecd-a48c57901089-video-59mb.mp4?uploadId=YjE1MzZmY2EtMTE4Zi00ZTg1LWEzZDYtODhlNDRiZTUwYzc4LjE2MzYyNjEwLTJiYzktNDhhNS05Njg4LTAwOGIwNzU1MGRhN3gxNzg2MTgyMDI4Njg4MDc0NzYy&partNumber=4&X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=minioadmin%2F20260808%2Fus-east-1%2Fs3%2Faws4_request&X-Amz-Date=20260808T094028Z&X-Amz-Expires=900&X-Amz-SignedHeaders=host&X-Amz-Signature=f7671fb34016d3f8bbed8d5a4ad02d0c2bf8e4deec34518fecf0128825f563ad

http://localhost:9000/chat-media/media/2/video/47e8594a-e324-4326-aecd-a48c57901089-video-59mb.mp4?uploadId=YjE1MzZmY2EtMTE4Zi00ZTg1LWEzZDYtODhlNDRiZTUwYzc4LjE2MzYyNjEwLTJiYzktNDhhNS05Njg4LTAwOGIwNzU1MGRhN3gxNzg2MTgyMDI4Njg4MDc0NzYy&partNumber=3&X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=minioadmin%2F20260808%2Fus-east-1%2Fs3%2Faws4_request&X-Amz-Date=20260808T094028Z&X-Amz-Expires=900&X-Amz-SignedHeaders=host&X-Amz-Signature=277e4d3b91378b2b2203c7408272dbd91641b6d94676311f111493aa0cd07649

http://localhost:9000/chat-media/media/2/video/47e8594a-e324-4326-aecd-a48c57901089-video-59mb.mp4?uploadId=YjE1MzZmY2EtMTE4Zi00ZTg1LWEzZDYtODhlNDRiZTUwYzc4LjE2MzYyNjEwLTJiYzktNDhhNS05Njg4LTAwOGIwNzU1MGRhN3gxNzg2MTgyMDI4Njg4MDc0NzYy&partNumber=2&X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=minioadmin%2F20260808%2Fus-east-1%2Fs3%2Faws4_request&X-Amz-Date=20260808T094028Z&X-Amz-Expires=900&X-Amz-SignedHeaders=host&X-Amz-Signature=3d7a5696d2245a7279bca14010fe3e5b97f497584dd85c7bd3318d2a39ed3f2a
```

4. Browser requests the presigned URLs for the last 2 parts in the multipart upload session (second batch):

```sh
curl --url 'http://localhost:9010/api/media/messages/upload-sessions/4a6e20b2-c10f-4431-9627-2337c52e340a/attachments/2be57809-9acd-4c9c-a251-dda7cc38f295/parts' \
  -H 'Accept: */*' \
  -H 'Content-Type: application/json' \
  -b 'CHATAPP_SESSION=M2QwYzc5Y2ItNGFjNC00N2IyLTkwOTYtZWE3ZjA4ODIwOTU5' \
  -H 'Origin: http://localhost:9010' \
  -H 'Referer: http://localhost:9010/group/3' \
  --data-raw '{"partNumbers":[5,6]}'
```

Response (with 2 presigned URLs for the last 2 parts):

```json
{
  "multipartUploadId": "YjE1MzZmY2EtMTE4Zi00ZTg1LWEzZDYtODhlNDRiZTUwYzc4LjE2MzYyNjEwLTJiYzktNDhhNS05Njg4LTAwOGIwNzU1MGRhN3gxNzg2MTgyMDI4Njg4MDc0NzYy",
  "parts": [
    {
      "partNumber": 5,
      "presignedUrl": "http://localhost:9000/chat-media/media/2/video/47e8594a-e324-4326-aecd-a48c57901089-video-59mb.mp4?uploadId=YjE1MzZmY2EtMTE4Zi00ZTg1LWEzZDYtODhlNDRiZTUwYzc4LjE2MzYyNjEwLTJiYzktNDhhNS05Njg4LTAwOGIwNzU1MGRhN3gxNzg2MTgyMDI4Njg4MDc0NzYy&partNumber=5&X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=minioadmin%2F20260808%2Fus-east-1%2Fs3%2Faws4_request&X-Amz-Date=20260808T094029Z&X-Amz-Expires=900&X-Amz-SignedHeaders=host&X-Amz-Signature=35cab9ce24d079d9fdf29d7fd7801d0402eef11ae69dab1adfd51cfc7c316cb6"
    },
    {
      "partNumber": 6,
      "presignedUrl": "http://localhost:9000/chat-media/media/2/video/47e8594a-e324-4326-aecd-a48c57901089-video-59mb.mp4?uploadId=YjE1MzZmY2EtMTE4Zi00ZTg1LWEzZDYtODhlNDRiZTUwYzc4LjE2MzYyNjEwLTJiYzktNDhhNS05Njg4LTAwOGIwNzU1MGRhN3gxNzg2MTgyMDI4Njg4MDc0NzYy&partNumber=6&X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=minioadmin%2F20260808%2Fus-east-1%2Fs3%2Faws4_request&X-Amz-Date=20260808T094029Z&X-Amz-Expires=900&X-Amz-SignedHeaders=host&X-Amz-Signature=b40c1a76517af28906d8ed3ead3bae7bc41f1b1827d32f94e2b15fb9e39f2f29"
    }
  ]
}
```

5. Browser uploads the last 2 parts to the presigned URLs (can be in any order) (directly to object storage):

```sh
http://localhost:9000/chat-media/media/2/video/47e8594a-e324-4326-aecd-a48c57901089-video-59mb.mp4?uploadId=YjE1MzZmY2EtMTE4Zi00ZTg1LWEzZDYtODhlNDRiZTUwYzc4LjE2MzYyNjEwLTJiYzktNDhhNS05Njg4LTAwOGIwNzU1MGRhN3gxNzg2MTgyMDI4Njg4MDc0NzYy&partNumber=6&X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=minioadmin%2F20260808%2Fus-east-1%2Fs3%2Faws4_request&X-Amz-Date=20260808T094029Z&X-Amz-Expires=900&X-Amz-SignedHeaders=host&X-Amz-Signature=b40c1a76517af28906d8ed3ead3bae7bc41f1b1827d32f94e2b15fb9e39f2f29

http://localhost:9000/chat-media/media/2/video/47e8594a-e324-4326-aecd-a48c57901089-video-59mb.mp4?uploadId=YjE1MzZmY2EtMTE4Zi00ZTg1LWEzZDYtODhlNDRiZTUwYzc4LjE2MzYyNjEwLTJiYzktNDhhNS05Njg4LTAwOGIwNzU1MGRhN3gxNzg2MTgyMDI4Njg4MDc0NzYy&partNumber=5&X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=minioadmin%2F20260808%2Fus-east-1%2Fs3%2Faws4_request&X-Amz-Date=20260808T094029Z&X-Amz-Expires=900&X-Amz-SignedHeaders=host&X-Amz-Signature=35cab9ce24d079d9fdf29d7fd7801d0402eef11ae69dab1adfd51cfc7c316cb6
```

6. Browser completes the multipart upload session:

```sh
curl --url 'http://localhost:9010/api/media/messages/upload-sessions/4a6e20b2-c10f-4431-9627-2337c52e340a/complete' \
  -H 'Accept: */*' \
  -H 'Content-Type: application/json' \
  -b 'CHATAPP_SESSION=M2QwYzc5Y2ItNGFjNC00N2IyLTkwOTYtZWE3ZjA4ODIwOTU5' \
  -H 'Origin: http://localhost:9010' \
  -H 'Referer: http://localhost:9010/group/3' \
  --data-raw '{"attachments":[{"attachmentId":"2be57809-9acd-4c9c-a251-dda7cc38f295","parts":[{"partNumber":1,"etag":"\"6db16d2e0b7711381b1fc9e5927dfc98\""},{"partNumber":2,"etag":"\"3e2fac3265a76ed8eb14ff583ee22edf\""},{"partNumber":3,"etag":"\"beca859828cd02ce836d188c8a85ec07\""},{"partNumber":4,"etag":"\"0ee7201ade962042fc3b131de5fe7959\""},{"partNumber":5,"etag":"\"db8e644c5501903f1ca5706a3dd4b187\""},{"partNumber":6,"etag":"\"f301aee9f578daeeb1cb4f4340c7c6e0\""}]}]}'
```

Response:

```json
{
  "id": 213601,
  "user": {
    "id": 2,
    "username": "vegeta",
    "fullname": "vegeta",
    "createdAt": "2025-12-11T06:39:57.664674"
  },
  "groupId": 3,
  "messageType": "VIDEO",
  "content": null,
  "systemEventType": null,
  "systemEventActor": null,
  "updatedBy": null,
  "updatedAt": null,
  "deletedBy": null,
  "deletedAt": null,
  "attachments": [
    {
      "id": 71,
      "attachmentOrder": 0,
      "originalFilename": "video-59mb.mp4",
      "mimeType": "video/mp4",
      "sizeBytes": 59453303,
      "status": "PROCESSING_PENDING",
      "scanStatus": "SCAN_PASSED",
      "width": null,
      "height": null,
      "durationMs": null,
      "contentUrl": "http://localhost:9000/chat-media/media/2/video/47e8594a-e324-4326-aecd-a48c57901089-video-59mb.mp4?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=minioadmin%2F20260808%2Fus-east-1%2Fs3%2Faws4_request&X-Amz-Date=20260808T094029Z&X-Amz-Expires=3600&X-Amz-SignedHeaders=host&X-Amz-Signature=97e82a37dfac15407e981c2d2b35e62170cb4d42475fa7f8af4647debe014540",
      "thumbnailUrl": null,
      "previewUrl": null,
      "transcodedUrl": null
    }
  ],
  "timestamp": "2026-08-08T16:40:29.518574"
}
```

# Example upload flow

- User clicks to Attach button
- User selects 2 photos, at this point, no API call is made yet
- User clicks to Send button, after this click, browser executes the following API:
  1. Init the upload session with 2 attachments (omitted cookies and headers for brevity):

     ```sh
      curl 'http://localhost:9010/api/media/messages/prepare' \
        --data-raw '{"chatScope":"GROUP","groupId":5,"messageType":"IMAGE","attachments":[{"filename":"1.jpg","mimeType":"image/jpeg","sizeBytes":33644},{"filename":"2.jpg","mimeType":"image/jpeg","sizeBytes":64558}]}'
     ```

     Response (with 2 presigned URLs):

     ```json
     {
       "uploadSessionId": "c1323cb4-1363-4730-8dee-f04339a5b29c",
       "messageType": "IMAGE",
       "chatScope": "GROUP",
       "expiresAt": "2026-07-01T00:27:55.696345",
       "retentionDays": 60,
       "limits": {
         "maxSizeBytes": 10485760,
         "maxAttachmentCount": 50
       },
       "attachments": [
         {
           "attachmentId": "264c6f87-458e-4780-887d-dd25645061fa",
           "objectKey": "media/2/image/efcc6fcc-4b98-43f5-bb23-0b61b4493775-1.jpg",
           "uploadStrategy": "SINGLE_PART",
           "presignedUrl": "http://localhost:9000/chat-media/media/2/image/efcc6fcc-4b98-43f5-bb23-0b61b4493775-1.jpg?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=minioadmin%2F20260630%2Fus-east-1%2Fs3%2Faws4_request&X-Amz-Date=20260630T171255Z&X-Amz-Expires=900&X-Amz-SignedHeaders=host&X-Amz-Signature=11afeb186582508a0ae940acb00084c70cd5f707eb125db072aff5206cf2b786",
           "multipartUploadId": null,
           "recommendedPartSize": null,
           "completeBy": "2026-07-01T00:27:55.696345"
         },
         {
           "attachmentId": "aa30cd7f-8081-4da3-8276-430294fa47f2",
           "objectKey": "media/2/image/21c00511-0a60-41a1-a02e-220b29f0b6e6-2.jpg",
           "uploadStrategy": "SINGLE_PART",
           "presignedUrl": "http://localhost:9000/chat-media/media/2/image/21c00511-0a60-41a1-a02e-220b29f0b6e6-2.jpg?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=minioadmin%2F20260630%2Fus-east-1%2Fs3%2Faws4_request&X-Amz-Date=20260630T171255Z&X-Amz-Expires=900&X-Amz-SignedHeaders=host&X-Amz-Signature=3b314ab9f85c09a6298330cd56c0325b8159361eaa954edaae41c4dcaa6783ad",
           "multipartUploadId": null,
           "recommendedPartSize": null,
           "completeBy": "2026-07-01T00:27:55.696345"
         }
       ]
     }
     ```

  2. Send 2 photos to presigned URLs (from `presignedUrl` field in the response above), each photo is sent to a separate presigned URL. Note: when copying them from browser devtool as cURL, we cannot copy the file content. Also: browser doesn't send cookies:
     ```sh
      curl 'http://localhost:9000/chat-media/media/2/image/efcc6fcc-4b98-43f5-bb23-0b61b4493775-1.jpg?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=minioadmin%2F20260630%2Fus-east-1%2Fs3%2Faws4_request&X-Amz-Date=20260630T171255Z&X-Amz-Expires=900&X-Amz-SignedHeaders=host&X-Amz-Signature=11afeb186582508a0ae940acb00084c70cd5f707eb125db072aff5206cf2b786' \
        -X 'PUT' \
        -H 'Accept: */*' \
        -H 'Accept-Language: en-US,en;q=0.9,vi;q=0.8' \
        -H 'Cache-Control: no-cache' \
        -H 'Connection: keep-alive' \
        -H 'Content-Length: 33644' \
        -H 'Content-Type: image/jpeg' \
        -H 'Origin: http://localhost:9010' \
        -H 'Pragma: no-cache' \
        -H 'Referer: http://localhost:9010/' \
        -H 'Sec-Fetch-Dest: empty' \
        -H 'Sec-Fetch-Mode: cors' \
        -H 'Sec-Fetch-Site: same-site' \
        -H 'User-Agent: Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36' \
        -H 'sec-ch-ua: "Google Chrome";v="149", "Chromium";v="149", "Not)A;Brand";v="24"' \
        -H 'sec-ch-ua-mobile: ?0' \
        -H 'sec-ch-ua-platform: "macOS"'
     ```
     ```sh
      curl 'http://localhost:9000/chat-media/media/2/image/21c00511-0a60-41a1-a02e-220b29f0b6e6-2.jpg?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=minioadmin%2F20260630%2Fus-east-1%2Fs3%2Faws4_request&X-Amz-Date=20260630T171255Z&X-Amz-Expires=900&X-Amz-SignedHeaders=host&X-Amz-Signature=3b314ab9f85c09a6298330cd56c0325b8159361eaa954edaae41c4dcaa6783ad' \
        -X 'PUT' \
        -H 'Accept: */*' \
        -H 'Accept-Language: en-US,en;q=0.9,vi;q=0.8' \
        -H 'Cache-Control: no-cache' \
        -H 'Connection: keep-alive' \
        -H 'Content-Length: 64558' \
        -H 'Content-Type: image/jpeg' \
        -H 'Origin: http://localhost:9010' \
        -H 'Pragma: no-cache' \
        -H 'Referer: http://localhost:9010/' \
        -H 'Sec-Fetch-Dest: empty' \
        -H 'Sec-Fetch-Mode: cors' \
        -H 'Sec-Fetch-Site: same-site' \
        -H 'User-Agent: Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36' \
        -H 'sec-ch-ua: "Google Chrome";v="149", "Chromium";v="149", "Not)A;Brand";v="24"' \
        -H 'sec-ch-ua-mobile: ?0' \
        -H 'sec-ch-ua-platform: "macOS"'
     ```
  3. Complete the upload session:

     ```sh
      curl 'http://localhost:9010/api/media/messages/upload-sessions/c1323cb4-1363-4730-8dee-f04339a5b29c/complete' \
        -H 'Accept: */*' \
        -H 'Accept-Language: en-US,en;q=0.9,vi;q=0.8' \
        -H 'Cache-Control: no-cache' \
        -H 'Connection: keep-alive' \
        -H 'Content-Type: application/json' \
        -b '_ga=GA1.1.1447835536.1775554647; _ga_YKJE90114C=GS2.1.s1775554646$o1$g0$t1775554654$j52$l0$h0; grafana_session=5380d0d5ce150d8db61a057584850dc2; grafana_session_expiry=1780678189; ph_phc_3ESMmY9SgqEAGBB6sMGK5ayYHkeUuknH2vP6FmWH9RA_posthog=%7B%22%24device_id%22%3A%22019ef94a-8d05-77ad-b30d-82a522781c04%22%2C%22distinct_id%22%3A%22anhtuta%22%2C%22%24sesid%22%3A%5B1782804643646%2C%22019f176f-ff3f-7acd-bf8c-613a48edbb9a%22%2C1782804643646%5D%2C%22%24epp%22%3Atrue%2C%22%24initial_person_info%22%3A%7B%22r%22%3A%22%24direct%22%2C%22u%22%3A%22http%3A%2F%2Flocalhost%3A4000%2F%22%7D%2C%22%24user_state%22%3A%22identified%22%7D; SESSION=YzhkMWIzZTEtZmE0Mi00NjBkLTlkOWYtZTljZmExOTBhOTYw' \
        -H 'Origin: http://localhost:9010' \
        -H 'Pragma: no-cache' \
        -H 'Referer: http://localhost:9010/group/5' \
        -H 'Sec-Fetch-Dest: empty' \
        -H 'Sec-Fetch-Mode: cors' \
        -H 'Sec-Fetch-Site: same-origin' \
        -H 'User-Agent: Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36' \
        -H 'sec-ch-ua: "Google Chrome";v="149", "Chromium";v="149", "Not)A;Brand";v="24"' \
        -H 'sec-ch-ua-mobile: ?0' \
        -H 'sec-ch-ua-platform: "macOS"' \
        --data-raw '{"attachments":[{"attachmentId":"264c6f87-458e-4780-887d-dd25645061fa","etag":"\"076305e376930b4f254e1ecdcdc08db0\""},{"attachmentId":"aa30cd7f-8081-4da3-8276-430294fa47f2","etag":"\"266ed872f2d5e5d1f299d67e274c6a23\""}]}'
     ```

     Response:

     ```json
     {
       "id": 213495,
       "user": {
         "id": 2,
         "username": "vegeta",
         "fullname": "vegeta",
         "createdAt": "2025-12-11T06:39:57.664674"
       },
       "groupId": 5,
       "messageType": "IMAGE",
       "content": null,
       "attachments": [
         {
           "id": 47,
           "attachmentOrder": 0,
           "originalFilename": "1.jpg",
           "mimeType": "image/jpeg",
           "sizeBytes": 33644,
           "status": "PROCESSING_PENDING",
           "scanStatus": "SCAN_PASSED",
           "width": null,
           "height": null,
           "durationMs": null,
           "contentUrl": "http://localhost:9000/chat-media/media/2/image/efcc6fcc-4b98-43f5-bb23-0b61b4493775-1.jpg?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=minioadmin%2F20260630%2Fus-east-1%2Fs3%2Faws4_request&X-Amz-Date=20260630T171256Z&X-Amz-Expires=3600&X-Amz-SignedHeaders=host&X-Amz-Signature=5f4b1be6c08c73e082fd2fdc88f2fd3faa1f9b1e3a0bc82eb90312d591d0fc83",
           "thumbnailUrl": null,
           "previewUrl": null,
           "transcodedUrl": null,
           "thumbnailObjectKey": null,
           "previewObjectKey": null,
           "transcodedObjectKey": null
         },
         {
           "id": 48,
           "attachmentOrder": 1,
           "originalFilename": "2.jpg",
           "mimeType": "image/jpeg",
           "sizeBytes": 64558,
           "status": "PROCESSING_PENDING",
           "scanStatus": "SCAN_PASSED",
           "width": null,
           "height": null,
           "durationMs": null,
           "contentUrl": "http://localhost:9000/chat-media/media/2/image/21c00511-0a60-41a1-a02e-220b29f0b6e6-2.jpg?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=minioadmin%2F20260630%2Fus-east-1%2Fs3%2Faws4_request&X-Amz-Date=20260630T171256Z&X-Amz-Expires=3600&X-Amz-SignedHeaders=host&X-Amz-Signature=24988f162b52270d6d4e2d205d93d4b0257fabe1b9847c1870dfd3412b80fbf5",
           "thumbnailUrl": null,
           "previewUrl": null,
           "transcodedUrl": null,
           "thumbnailObjectKey": null,
           "previewObjectKey": null,
           "transcodedObjectKey": null
         }
       ],
       "timestamp": "2026-07-01T00:12:55.974034"
     }
     ```

## Why can't we copy the file content when copying from browser devtool as cURL?

When your code executes `xhr.send(file)`, the browser does not read the file into memory right away; it hands a reference of the OS file pointer directly to the browser's underlying network stack to stream it efficiently without freezing the UI.
Because the binary data lives in a file descriptor outside the standard JavaScript context:

1.  DevTools Security Constraints: Chrome's DevTools Network Panel chooses not to serialize raw binary file streams into the clipboard string. Dumping megabytes or gigabytes of unencoded binary data into your system clipboard would crash the browser or the terminal you paste it into.
2.  Missing `--data` representation: Because DevTools treats the streamed File object as an un-cacheable binary payload, it strips out the data entirely during the "Copy as cURL" mapping, leaving you with only the headers and the target pre-signed AWS S3/GCS URL.

Ref: Google AI

# Explain the presigned URL format

Let use the first presigned URL above as an example:

```
http://localhost:9000/chat-media/media/2/image/efcc6fcc-4b98-43f5-bb23-0b61b4493775-1.jpg?
  X-Amz-Algorithm=AWS4-HMAC-SHA256&
  X-Amz-Credential=minioadmin/20260630/us-east-1/s3/aws4_request&
  X-Amz-Date=20260630T171255Z&
  X-Amz-Expires=900&
  X-Amz-SignedHeaders=host&
  X-Amz-Signature=11afeb186582508a0ae940acb00084c70cd5f707eb125db072aff5206cf2b786
```

Here is a breakdown of that URL and how MinIO checks it.

## URL anatomy

```
http://localhost:9000/chat-media/media/2/image/efcc6fcc-4b98-43f5-bb23-0b61b4493775-1.jpg
│                      │         │                                                      │
│                      │         └── object key (path inside bucket)                    │
│                      └── bucket name                                                  │
└── MinIO endpoint (port 9000)                                                          │
                                                                                        │
?X-Amz-Algorithm=...&X-Amz-Credential=...&X-Amz-Date=...&X-Amz-Expires=...              │
 &X-Amz-SignedHeaders=host&X-Amz-Signature=...                                          │
                                                                                        │
└── AWS Signature Version 4 (SigV4) — temporary auth baked into the URL ────────────────┘
```

### Path part (where the file goes)

Your app uses **path-style** URLs: `/{bucket}/{objectKey}`.

| Segment                            | Meaning                                             |
| ---------------------------------- | --------------------------------------------------- |
| `chat-media`                       | Bucket name (from config `CHAT_MEDIA_MINIO_BUCKET`) |
| `media/2/image/efcc6fcc-...-1.jpg` | Object key — the file’s path inside the bucket      |

The object key is built in your backend as:

```java
// chat-app-backend/src/main/java/com/hello/chatapp/service/MediaUploadSessionService.java
private String buildObjectKey(User user, MessageType messageType, String filename) {
    String safeFilename = filename == null ? "upload.bin" : filename.replaceAll("[^A-Za-z0-9._-]", "_");
    Long userId = Objects.requireNonNull(user.getId());
    return "media/" + userId + "/" + messageType.name().toLowerCase() + "/" + UUID.randomUUID() + "-" + safeFilename;
}
```

So for your example:

- `media/` — prefix for all chat media
- `2/` — user id
- `image/` — message type (`IMAGE` → `image`)
- `efcc6fcc-4b98-43f5-bb23-0b61b4493775-1.jpg` — random UUID + sanitized original filename

### Query part (temporary permission)

| Parameter             | Your value                                      | Meaning                                                             |
| --------------------- | ----------------------------------------------- | ------------------------------------------------------------------- |
| `X-Amz-Algorithm`     | `AWS4-HMAC-SHA256`                              | SigV4 signing scheme (same as AWS S3)                               |
| `X-Amz-Credential`    | `minioadmin/20260630/us-east-1/s3/aws4_request` | Access key + date + region + service                                |
| `X-Amz-Date`          | `20260630T171255Z`                              | When the URL was signed (UTC)                                       |
| `X-Amz-Expires`       | `900`                                           | Valid for 900 seconds (15 min) after `X-Amz-Date`                   |
| `X-Amz-SignedHeaders` | `host`                                          | Only the `Host` header was included in the signature                |
| `X-Amz-Signature`     | `11afeb18...`                                   | HMAC proving the URL was signed by someone who knows the secret key |

`X-Amz-Credential` decoded from URL encoding (`%2F` → `/`):

```
minioadmin / 20260630 / us-east-1 / s3 / aws4_request
   │            │           │        │
 access key   sign date   region   service (S3 API)
```

Your backend creates this URL with the MinIO Java SDK:

```java
// chat-app-backend/src/main/java/com/hello/chatapp/storage/MinioObjectStorageProvider.java
private String getPresignedObjectUrl(Http.Method method, String objectKey, Map<String, String> extraQueryParams) {
    try {
        return minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                .method(method)
                .bucket(mediaStorageProperties.getMinio().getBucket())
                .object(objectKey)
                .expiry(resolveExpiryMinutes(method), TimeUnit.MINUTES)
                .extraQueryParams(extraQueryParams)
                .build());
    }
}
```

That uses `minioadmin` + its **secret key** (never sent to the browser) to compute `X-Amz-Signature`.

## How MinIO verifies the request

When the browser sends `PUT` to that URL, MinIO does **not** use your Spring session. It re-validates the SigV4 signature.

```mermaid
sequenceDiagram
    participant Backend
    participant Browser
    participant MinIO

    Note over Backend: Has accessKey + secretKey
    Backend->>Backend: Build canonical request<br/>(PUT, bucket, object key, query params, host)
    Backend->>Backend: HMAC-SHA256 with secretKey → Signature
    Backend-->>Browser: presignedUrl (includes Signature)

    Browser->>MinIO: PUT /chat-media/media/2/image/...jpg<br/>?X-Amz-* query params<br/>Body = file bytes

    MinIO->>MinIO: 1. Parse X-Amz-Credential → access key
    MinIO->>MinIO: 2. Look up secret key for minioadmin
    MinIO->>MinIO: 3. Check X-Amz-Date + Expires not past deadline
    MinIO->>MinIO: 4. Rebuild same canonical request from incoming PUT
    MinIO->>MinIO: 5. Recompute signature with secret key
    MinIO->>MinIO: 6. Compare with X-Amz-Signature

    alt signature matches + not expired + PUT allowed
        MinIO-->>Browser: 200 OK + ETag
    else invalid
        MinIO-->>Browser: 403 Forbidden
    end
```

### Step-by-step (SigV4, simplified)

**1. Generation (backend, once)**

The MinIO SDK builds a **canonical request** from fixed inputs:

- HTTP method: `PUT` (locked in at sign time)
- Canonical URI: `/chat-media/media/2/image/efcc6fcc-...-1.jpg`
- Canonical query string: sorted `X-Amz-*` params (without `Signature` yet)
- Canonical headers: `host:localhost:9000` (because `SignedHeaders=host`)
- Payload hash: `UNSIGNED-PAYLOAD` for presigned PUT (body is not part of the signature)

Then it builds a **string to sign**:

```
AWS4-HMAC-SHA256
20260630T171255Z
20260630/us-east-1/s3/aws4_request
<hash of canonical request>
```

It derives a signing key from the **secret key** (via a chain of HMACs: date → region → service → `aws4_request`), then HMACs the string to sign → `X-Amz-Signature`.

**2. Verification (MinIO, on each PUT)**

MinIO repeats the same math on the **incoming** request:

1. Read `X-Amz-Credential` → find secret for `minioadmin`
2. Reject if `now > X-Amz-Date + X-Amz-Expires` (your URL expires 15 minutes after `17:12:55Z`)
3. Rebuild canonical request from actual method, path, query string, and `Host`
4. Recompute signature
5. Constant-time compare with `X-Amz-Signature`

If they match → request is authorized for that one object and that HTTP method.

## What the signature locks in (and what it does not)

**Locked in (changing these breaks the signature):**

- Bucket: `chat-media`
- Object key: `media/2/image/efcc6fcc-...-1.jpg`
- HTTP method: `PUT` (a `GET` with the same URL would fail)
- Query parameters that were signed
- `Host` header value

**Not locked in for your URL:**

- `X-Amz-SignedHeaders=host` means only `Host` is signed
- `Content-Type: image/jpeg` is **not** in the signature — the client may set it (your frontend does), but MinIO does not require it to match a presigned value
- The file body is **not** hashed into the signature (`UNSIGNED-PAYLOAD`) — any bytes can be uploaded to that key

So the URL is really: _“Whoever has this link may PUT **something** to **this exact object path** until **this time**.”_ It is not a hash of the file content.

## Why this design

| Piece                   | Role                                                                        |
| ----------------------- | --------------------------------------------------------------------------- |
| Path (`chat-media/...`) | Tells MinIO **where** to store the object                                   |
| `X-Amz-*` query params  | Prove the PUT was **authorized** without sending secret keys to the browser |
| Request body            | The actual **file bytes** (separate from the URL)                           |

The browser never sees `minioadmin`’s secret key. It only gets a time-limited, object-specific capability URL. Your backend stays in control of **who** gets a URL (`/prepare` requires login), while MinIO handles the heavy binary upload without going through Spring.

## Quick mental model

Think of the URL like a **valet parking ticket**:

- **Path** = which parking spot (bucket + object key)
- **X-Amz-Date + Expires** = ticket valid until 17:27 UTC
- **X-Amz-Signature** = tamper-proof stamp from someone with the master key (secret)
- **PUT body** = the car you’re actually parking (not printed on the ticket)

If you change the spot, the method, or the expiry params, the stamp no longer matches → MinIO returns 403.

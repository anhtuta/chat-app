# Media Chat Support Draft Design

## Current Problem

The current chat system is still designed for **text-only messages**:

- `messages` stores a single `content` field for message body text
- `MessageRequest` accepts only `content` and `groupId`
- `MessageResponse` returns only text content and basic sender metadata
- There is no upload lifecycle, media metadata model, storage abstraction, or malware/processing pipeline

Supporting images, videos, audio, and arbitrary files requires more than just adding an upload endpoint. We need a design that covers:

- Message schema changes
- Secure object storage uploads
- Validation and abuse protection
- Asynchronous processing (scan, thumbnail, compression/transcode)
- Frontend upload UX and message rendering
- Local development with MinIO and cloud production on AWS/GCP/Azure

The main design goal is to support media messages without turning the backend into a file-transfer bottleneck, while keeping the message domain, security model, and future scale path clean.

## Functional Requirements

- Users can send these media types:
  - Image
  - Video
  - Audio
  - File
- Users can optionally add a caption/text body to a media message
- Users can upload large files with resumable or multipart upload behavior
- Frontend shows upload progress for large uploads
- Frontend renders thumbnails / previews when applicable
- Messages remain ordered in chat history alongside text messages
- Media messages can be loaded through existing message-history APIs with extra media metadata
- Download/view access remains limited to authorized chat participants
- Failed, canceled, or abandoned uploads do not create broken permanent messages

## Non-Functional Requirements

- Keep application servers out of the hot path for large binary transfer whenever possible
- Enforce file size limits:
  - Images: up to 10 MB
  - Audio: up to 50 MB
  - Video: up to 200 MB
  - Generic files: up to 20 MB
- Validate both declared MIME type and server-detected content type
- Rate limit upload initiation and completion to reduce abuse
- Support local object storage via MinIO and cloud object storage in production
- Allow asynchronous scanning and media processing without blocking the full app
- Make processing failures visible and recoverable
- Keep storage/provider-specific details behind an abstraction layer
- Preserve backward compatibility for existing text messages

## Clarifying Questions

- Should one message support **multiple attachments**, or exactly **one attachment per message** in the first version?
  - Answer: Users can send multiple photos in one message, but other types of media: only one attachment per message.
- Should captions be supported for all media types, or only for image/video?
  - Answer: Yes, captions should be supported for all media types, but it's optional.
- Should an uploaded media message become visible to other users:
  - only after upload completes,
  - only after malware scan passes,
  - or immediately with a temporary "processing" state?
  - Answer: only after upload completes, and only after malware scan passes.
- Do we want inline playback for video/audio in phase 1, or only download/open behavior?
  - Answer: inline playback for video/audio in phase 1.
- Do we need message editing for captions after upload?
  - Answer: No, we do not need message editing for captions after upload.
- Do we need attachment deletion independent of message deletion?
  - Answer: No, we do not need attachment deletion independent of message deletion.
- Should some file types be blocked even if they pass malware scan, for example executable or script formats?
  - Answer: No, we do not need to block any file types after they pass malware scan.
- Do we need image/video compression to happen synchronously before message delivery, or can users see the original first and optimized derivatives later?
  - Answer: Yes, image/video compression should happen synchronously before message delivery.
- What retention policy should apply to uploaded media and orphaned uploads?
  - Answer: We should keep uploaded media for at least 30 days, and delete them after 60 days.
- Do we need tenant/region-specific storage residency later?
  - Answer: No, we do not need tenant/region-specific storage residency later.
- Should public chat also support media, or only group chat in phase 1?
  - Answer: Yes, public chat should support media in phase 1.
- What are the maximum video duration and audio duration limits, if any?
  - Answer: No limitation on duration, only size limits.

## Possible Solutions

### 1. Upload Through Backend Application Servers

- Client uploads media bytes directly to the Spring backend
- Backend validates, scans, and writes the object to storage
- Backend then creates the chat message

**Pros**

- Simplest mental model
- Easier to enforce auth and validation in one place
- Works even if the frontend cannot use multipart object-storage APIs

**Cons**

- Backend becomes the bandwidth bottleneck
- Large-file uploads compete with chat API/WebSocket capacity
- Harder to scale economically
- Resume/chunk behavior becomes our responsibility
- Production architecture differs less cleanly between MinIO and cloud providers

**Recommendation for our problem:** No

**When I'd use it**

- Small internal app
- Strict network boundaries where clients cannot access object storage directly
- Very small file sizes and low traffic

### 2. Direct Upload to Object Storage via Presigned URLs With Backend-Controlled Metadata

- Backend issues a short-lived upload intent plus presigned URL(s)
- Client uploads directly to object storage
- Client calls a completion endpoint after successful upload
- Backend verifies uploaded object metadata and creates or finalizes the message
- Background workers handle malware scan, thumbnails, and optional compression/transcoding

**Pros**

- Best balance of scalability, security, and implementation effort
- Keeps large binary transfer off the app servers
- Maps well to MinIO locally and S3/GCS/Azure Blob in production
- Supports multipart/resumable upload cleanly
- Easy to add CDN and derivative assets later

**Cons**

- More moving parts than backend proxy upload
- Requires a clear upload lifecycle and cleanup of abandoned uploads
- Cloud providers differ slightly for multipart semantics and signed URL behavior

**Recommendation for our problem:** Yes

### 3. Managed Media Platform / CDN-Centric Media Service

- Use a higher-level media platform or specialized service for uploads, transcoding, thumbnails, and delivery
- Chat app stores references and metadata instead of owning the full pipeline

**Pros**

- Fastest route to advanced video/audio features
- Built-in transcoding and derivative generation
- Less operational burden for media processing

**Cons**

- Higher vendor lock-in
- More expensive
- Less control over security/compliance details
- Harder to preserve a provider-agnostic MinIO-local setup

**Recommendation for our problem:** No for initial rollout

## Recommendation

Recommendation path:

1. Phase 1: Add a provider-agnostic object storage abstraction and direct-upload design using presigned URLs
2. Phase 2: Support images and generic files first, with upload progress, short-lived signed download URLs, and basic thumbnails for images
3. Phase 3: Add video/audio processing, generated thumbnails/waveforms, and optimized derivative delivery
4. Phase 4: Add stricter abuse controls, moderation workflows, lifecycle cleanup, and CDN-backed caching

## High-Level Architecture

### Use cases

1. Client asks backend to create an upload intent
2. Backend authenticates user, validates requested media type/size, applies rate limits, and returns presigned upload data
3. Client uploads directly to object storage
4. Client calls backend to complete the upload
5. Backend verifies the uploaded object and creates a media message in a `PROCESSING` or `READY` state
6. Background workers run malware scan, thumbnail generation, and optional compression/transcoding
7. When media is ready, normal chat delivery and history APIs return message metadata plus view/download URLs

### Proposed Domain Model

#### 1. Add `messageType` to `messages`

Yes, the design should add a new column to the message table to identify message kind.

Suggested enum:

- `TEXT`
- `IMAGE`
- `VIDEO`
- `AUDIO`
- `FILE`
- `SYSTEM` (optional if we want to reserve room for future non-user messages, such as "User1 has been kicked out of the group by User2", "User3 has left the group", "User4 joined the group", etc.)

Why:

- Rendering logic depends on message type
- Validation rules depend on message type
- Sidebar/latest-message preview often differs by type, for example "Photo" or a filename instead of raw text
- Keeping type on the main `messages` row makes message history queries efficient

#### 2. Add a dedicated media metadata table

Do **not** overload `content` with storage metadata. Keep text/caption concerns separate from file metadata.

Suggested table: `message_media`

Suggested fields:

- `id`
- `message_id`
- `storage_provider`
- `bucket`
- `object_key`
- `original_filename`
- `declared_mime_type`
- `detected_mime_type`
- `size_bytes`
- `checksum_sha256`
- `status` (`UPLOADING`, `PROCESSING`, `READY`, `FAILED`, `BLOCKED`, `DELETED`)
- `scan_status` (`PENDING`, `CLEAN`, `INFECTED`, `ERROR`)
- `width`, `height` for images/video when available
- `duration_ms` for audio/video when available
- `thumbnail_object_key` when available
- `preview_object_key` when available
- `transcoded_object_key` when available
- `created_at`, `updated_at`

Caption strategy:

- Keep `messages.content` for user-entered caption text
- For pure file-only messages, `content` may be null or empty

#### 3. Add an upload-tracking table

Suggested table: `media_uploads`

Purpose:

- Track presigned upload intents before a message exists
- Prevent orphaned or spoofed completion calls
- Support expiration, retries, and cleanup

Suggested fields:

- `upload_id`
- `user_id`
- `group_id`
- `requested_message_type`
- `requested_filename`
- `requested_size_bytes`
- `requested_mime_type`
- `storage_provider`
- `bucket`
- `object_key`
- `multipart_upload_id` (nullable)
- `status` (`INITIATED`, `UPLOADED`, `COMPLETED`, `EXPIRED`, `CANCELED`, `FAILED`)
- `expires_at`

### Storage Abstraction

Create a storage-provider interface in the backend so business logic does not depend on MinIO/S3/GCS/Azure specifics.

Suggested responsibilities:

- Create upload intent / presigned URL(s)
- Complete multipart upload
- Abort multipart upload
- Generate short-lived signed download/view URLs
- Delete objects
- Read object metadata / HEAD

Suggested implementations:

- `MinioStorageProvider` for local development
- `S3StorageProvider` for AWS production
- Optional later:
  - `GcsStorageProvider`
  - `AzureBlobStorageProvider`

Recommendation:

- Keep a single internal contract and provider-specific adapters
- Avoid leaking provider-specific terminology into controller DTOs where possible

### API Draft

#### 1. Create upload intent

`POST /api/media/uploads`

Request fields:

- `groupId`
- `messageType`
- `filename`
- `sizeBytes`
- `mimeType`
- `multipart` flag or backend decision based on size threshold

Response fields:

- `uploadId`
- `storageProvider`
- `objectKey`
- `uploadStrategy` (`SINGLE_PART`, `MULTIPART`)
- `presignedUrl` for small single-part uploads
- multipart instructions for large uploads:
  - `uploadId`
  - part size guidance
  - endpoint(s) to request part URLs or a prepared list of part URLs
- expiration time
- max allowed size for that media type

#### 2. Complete upload

`POST /api/media/uploads/{uploadId}/complete`

Request fields:

- multipart completion metadata if applicable
- optional caption text

Response:

- created `MessageResponse` with media payload, or a pending/processing variant

#### 3. Fetch/render media in chat history

Extend existing message-history responses so media messages include:

- `messageType`
- `caption`
- `media.status`
- `media.originalFilename`
- `media.mimeType`
- `media.sizeBytes`
- `media.thumbnailUrl` (short-lived signed URL when available)
- `media.contentUrl` or `downloadUrl`
- `media.width`, `media.height`, `media.durationMs`

### Upload Lifecycle

#### Recommended flow

1. User selects file in frontend
2. Frontend validates basic file type/size before starting
3. Frontend requests upload intent from backend
4. Backend validates auth, membership, limits, and rate limits
5. Client uploads directly to object storage
6. Client calls completion endpoint
7. Backend performs server-side verification:
   - object exists
   - size matches allowed limits
   - detected content type is acceptable
   - upload belongs to caller
8. Backend creates message and media metadata
9. Background processing updates media status
10. Frontend updates placeholder/progress UI until ready

#### Recommended visibility rule

Default recommendation:

- Sender sees a local optimistic placeholder while uploading
- Other users should not see the message until upload completes and the object passes initial verification
- If malware scanning is asynchronous, the message can enter `PROCESSING`
- If scan fails, mark the media as `BLOCKED` and do not expose a download URL

This is safer than publishing media immediately and trying to revoke it later.

### Validation and Security

#### Validation

- Validate allowed message type against requested MIME type
- Validate actual object metadata after upload, not only the client-declared MIME type
- Use content sniffing or provider metadata inspection server-side
- Enforce per-type size limits
- Reject zero-byte or truncated uploads
- Consider a restricted allowlist for risky file categories

#### Content Security

- Use short-lived signed URLs for download/view access
- Do not expose raw object keys publicly
- Store objects under opaque keys, not user-provided filenames
- Sanitize displayed filenames in the UI
- Set safe download headers where needed
- Strip dangerous inline rendering for unsupported formats

#### Malware Scanning

Recommended approach:

- Upload into a temporary/quarantine prefix
- Run malware scan asynchronously
- Promote to a clean/serving prefix only after scan passes, or mark the media blocked

If quarantine/promotion is too much for phase 1, at minimum:

- keep signed URLs unavailable until scan passes, or
- restrict visibility until scan completes

### Upload Rate Limiting

Yes, Redis is a good fit for upload rate limiting.

Recommended controls:

- limit upload intent creation per user per minute
- limit total uploaded bytes per user per time window
- limit concurrent active uploads per user
- optional group-level abuse limits

Why not only request count:

- 100 MB files and 100 KB files create very different costs

Suggested Redis keys:

- `rate:media:intent:user:{userId}`
- `rate:media:bytes:user:{userId}`
- `rate:media:active:user:{userId}`

### Media Processing

#### Images

- generate at least one thumbnail size
- optionally compress oversized images
- preserve original image for download when required
- consider EXIF stripping and orientation normalization

#### Video

- generate poster thumbnail
- optionally generate streaming-friendly derivative later
- capture duration, width, and height metadata

#### Audio

- capture duration and codec metadata
- optionally transcode to a normalized compressed format later
- optionally generate waveform data later

#### Generic files

- no transcoding
- keep filename, MIME type, and size metadata
- use download-focused UI

Recommendation:

- Make compression/transcoding asynchronous
- Treat thumbnails as higher priority than heavy transcoding
- Do not block initial rollout on full media optimization

### Frontend UX

- Show optimistic local placeholder for upload in progress
- Show upload progress percentage
- Support cancel/retry for failed uploads
- Use multipart upload for large video/audio/file payloads
- Lazy-load thumbnails and large media
- Render clear states:
  - uploading
  - processing
  - ready
  - failed
  - blocked
- For image/video, prefer preview cards
- For audio, show compact inline player only if supported in phase 1
- For generic files, show filename, size, icon, and download/open action

### Media Caching

Recommended approach:

- Cache derived thumbnails/previews aggressively
- Keep signed full-content URLs short-lived
- Add CDN in production later for clean media only
- Avoid caching blocked or processing assets in public layers

For local development:

- direct MinIO access or signed URLs are sufficient

### Other Important Considerations

- **Orphan cleanup:** unfinished uploads must expire and be deleted
- **Deletion semantics:** define whether deleting a message hard-deletes, soft-deletes, or tombstones media
- **Quota management:** decide whether users or groups have total storage quotas
- **Auditability:** log upload intent, completion, scan result, and deletion events
- **Moderation:** consider future admin review for reported media
- **Accessibility:** captions/alt text are likely not phase 1 requirements, but should stay possible
- **Search/indexing:** filenames and captions may later become searchable
- **Privacy/compliance:** retention, legal hold, and regional storage requirements may matter later
- **Cost controls:** video storage and egress can dominate costs quickly

### API / Contract / Config Impacts

- New message-response contract for media metadata
- New upload APIs and DTOs
- New background processing components
- New configuration for:
  - storage provider selection
  - buckets/prefixes
  - signed URL TTL
  - max file sizes by message type
  - allowed MIME types
  - multipart threshold
  - rate limiting thresholds
  - malware scanner integration

### Backward Compatibility and Rollout Notes

- Existing text messages remain valid with `messageType = TEXT`
- New database fields should be nullable or have safe defaults during rollout
- Older clients should fail safely when receiving unknown message types
- Group latest-message preview logic may need a type-aware summary, for example:
  - image -> "Photo"
  - video -> "Video"
  - audio -> "Audio"
  - file -> filename or "File"

## Chosen Solution + Implementation

Status: **draft only, not implemented**

Use **direct client upload to object storage via backend-issued upload intents**. Keep message persistence in the chat backend, but keep large binary transfer out of the app servers.

## Future Higher-Scale Path

- Move derivative generation and scanning to dedicated worker queues
- Add CDN in front of clean-media delivery
- Add adaptive video streaming derivatives if in-app playback becomes important
- Add deduplication by checksum for repeated uploads
- Add organization/group storage quotas
- Add stronger moderation workflows and abuse review queues
- Add cross-region storage and residency policies if the product grows internationally

# Feature 29 — Manual test cases

Use this list to exercise what is **already implemented** in [29_MEDIA_PROCESSING_SERVICE.md](./29_MEDIA_PROCESSING_SERVICE.md) (Phases 1–9). Phases 10–13 (HLS, OCR, speech-to-text, image derivatives, durable job table) are **out of scope**.

Mark each case `Pass` / `Fail` / `Blocked` as you go. A sample websocket payload for a successful MOV upload is in [logs/29_user-upload-video-24mb.md](./logs/29_user-upload-video-24mb.md).

## Scope

In scope:

- Worker scaffold, health, local trigger
- MinIO download/upload, workspace cleanup
- Video metadata (ffprobe), poster JPEG, canonical MP4 (reuse / remux / re-encode)
- Optional 480p mobile rendition
- RabbitMQ job after chat message commit
- Internal callback, URL switch, delete original after success
- Frontend poster / playback / download / `videoSources` selection

Out of scope (do not expect these to work yet):

- HLS / adaptive streaming
- Image thumbnails, image OCR, video OCR, speech-to-text, `media_extracted_text`
- Durable `MediaProcessingJob` table / outbox recovery
- S3 original-object deletion (MinIO only)

## How to run locally

### Full chat path (most cases)

1. MinIO, Postgres, Redis, RabbitMQ up (usual `chat-app-backend` docker compose).
2. `ffmpeg` and `ffprobe` on `PATH`.
3. Backend `.env.local`:
   - `MEDIA_PROCESSING_ENABLED=true`
   - `MEDIA_PROCESSING_CALLBACK_TOKEN` set to a known value (must match the worker)
4. Worker `media-processing/.env` (from `.env.example`):
   - `MEDIA_PROCESSING_WORKER_ENABLED=true`
   - `MEDIA_PROCESSING_CALLBACK_ENABLED=true`
   - same `MEDIA_PROCESSING_CALLBACK_TOKEN`
   - `CHAT_APP_BACKEND_URL=http://localhost:9010`
   - `RABBITMQ_URI=amqp://guest:guest@localhost:5672`
   - `MEDIA_PROCESSING_LOCAL_TRIGGER_ENABLED=false` for chat-path tests
   - `MEDIA_PROCESSING_STORAGE_LOCAL_DEVELOPMENT=true` so `http://localhost:9000` is allowed
5. Start backend (`make run.be` or equivalent) and worker (`cd media-processing && make run`).
6. Open two browsers (or two users) in the same group. Watch websocket / network for `MessageResponse`.
7. MinIO console: bucket `chat-media`.

Ports: backend `9010`, worker `9020`, MinIO `9000`.

### Worker-only path (local trigger)

Use when you want to skip chat-app-backend and RabbitMQ.

1. Put a video object in MinIO `chat-media`.
2. Worker: `MEDIA_PROCESSING_WORKER_ENABLED=false` is OK (handler still runs over HTTP).
3. `MEDIA_PROCESSING_LOCAL_TRIGGER_ENABLED=true`
4. `MEDIA_PROCESSING_CALLBACK_ENABLED=false` (logging sink only).
5. `POST http://localhost:9020/local/media-processing/jobs` with `{"objectKey":"<key>"}`.

Default targets for that endpoint: `METADATA`, `THUMBNAIL`, `TRANSCODE`. Optional body: `bucket`, `requestedMimeType`, `processingTargets`, `jobId`, `messageId`, `mediaId`.

### Suggested fixtures

| Fixture | Why |
| --- | --- |
| Phone/camera **MOV** taller than 480p, duration ≥ 15s, file ≥ 8MB | Remux or re-encode + 480p (same class as `sample-video-24mb.mov`) |
| **WebM** VP9/Opus | Re-encode to H.264 + AAC |
| **MP4** already H.264 + AAC | Reuse original; no `{stem}.transcoded.mp4` sibling |
| **MOV** that is already H.264 + AAC | Remux into MP4 |
| Short clip (< 15s) **or** already ≤ 480p **or** canonical MP4 < 8MB | Canonical still succeeds; **no** `{stem}.480p.mp4` |
| Zero-byte object / missing key | Source failure |
| Image (jpeg/png) | Chat publishes `MEDIA_READY` without the video worker |
| AVI/MKV if the UI lets you pick it | Upload allowlist vs worker allowlist |

---

## A. Process isolation and health

### A1. Worker liveness

- **Steps:** `GET http://localhost:9020/health` (no auth).
- **Expect:** `200` and `{"status":"UP"}`. This does **not** prove MinIO/RabbitMQ.

### A2. Worker is not a chat API

- **Steps:** Call chat endpoints (`/api/messages`, login, etc.) on port `9020`.
- **Expect:** No user-facing chat APIs. Only health, and local trigger when enabled.

### A3. Chat stays usable while a video is processing

- **Steps:** Upload a large video. Immediately send a text message and load history in another tab.
- **Expect:** Text send, websocket delivery, and history are not blocked by ffmpeg. Video message appears as `PROCESSING_PENDING` first.

---

## B. Local trigger (no backend / no RabbitMQ)

Keep `media-processing.local-trigger.enabled=true` only for this section. Turn it off afterward.

### B1. Local trigger disabled by default

- **Setup:** `MEDIA_PROCESSING_LOCAL_TRIGGER_ENABLED=false`.
- **Steps:** `POST http://localhost:9020/local/media-processing/jobs` with a valid body.
- **Expect:** `404` (controller bean not loaded).

### B2. Happy path — MOV/MP4/WebM

- **Setup:** Object in `chat-media`. Trigger enabled. ffmpeg/ffprobe on PATH.
- **Steps:** `POST /local/media-processing/jobs` with `{"objectKey":"path/to/video.mov"}`.
- **Expect:**
  - Response `status` is `MEDIA_READY`.
  - Result includes metadata (duration, width, height, codecs).
  - `thumbnailObjectKey` is `{stem}.thumbnail.jpg` in MinIO (JPEG).
  - If source is **not** already H.264+AAC MP4: `transcodedObjectKey` is `{stem}.transcoded.mp4`.
  - If source **is** already H.264+AAC MP4: `transcodedObjectKey` equals the original key; no extra transcoded object.
  - Original object is **not** deleted on this path (deletion is backend Phase 7).

### B3. Reuse original (H.264 + AAC MP4)

- **Steps:** Upload/put a chat-friendly MP4; trigger it.
- **Expect:** `MEDIA_READY`, reuse (no `{stem}.transcoded.mp4`). Poster may still be written. ffprobe on the original should show h264 + aac (or no audio).

### B4. Remux (H.264 + AAC in MOV)

- **Steps:** Trigger a `.mov` that is already h264/aac.
- **Expect:** `{stem}.transcoded.mp4` exists. `ffprobe` on that file: MP4, h264, aac, `faststart` (moov near start). Processing should be faster than a full re-encode.

### B5. Re-encode (WebM / HEVC / non-AAC audio)

- **Steps:** Trigger a VP9/Opus WebM or HEVC MOV.
- **Expect:** `{stem}.transcoded.mp4` is H.264 + AAC (or video-only with `-an` if no audio). File plays in the browser.

### B6. Metadata-only job stays in progress

- **Steps:** Body `"processingTargets":["METADATA"]` (thumbnail/transcode flags still on).
- **Expect:** Handler can complete metadata and return `MEDIA_READY` if that is the only requested target. If you request `METADATA` plus a target that is not implemented (e.g. `IMAGE_OCR`), expect `PROCESSING_IN_PROGRESS` with that target still pending — not `PROCESSING_FAILED`.

### B7. Missing object

- **Steps:** `objectKey` that does not exist.
- **Expect:** `PROCESSING_FAILED`. Worker log reason `SOURCE_MISSING`. No derivative objects.

### B8. Zero-byte object

- **Steps:** Put an empty object; trigger it.
- **Expect:** `PROCESSING_FAILED`, reason `SOURCE_CORRUPTED` (current heuristic: empty download).

### B9. Duplicate `jobId` while in progress / after success

- **Steps:** Same `jobId` twice quickly on a long video; then again after `MEDIA_READY`.
- **Expect:** Second in-flight call is `SKIPPED_DUPLICATE`. After success, the same `jobId` is skipped again (in-memory store; restarting the JVM clears it).

### B10. Unsupported container at transcode

- **Steps:** Put an AVI/MKV in MinIO and trigger it (bypass chat upload).
- **Expect:** `PROCESSING_FAILED` with unsupported format (allowlist is MP4 / MOV / WebM at transcode time).

### B11. Feature flag off — transcode

- **Setup:** `media-processing.worker.feature-flags.video-transcode=false`.
- **Steps:** Default local job.
- **Expect:** Transcode not run; no new `.transcoded.mp4`. Metadata/poster may still run. Status is not a full chat-ready canonical switch.

### B12. Feature flag off — mobile rendition

- **Setup:** `video-mobile-renditions=false`, transcode on, tall/long/large source.
- **Expect:** Canonical MP4 + poster succeed. **No** `{stem}.480p.mp4`.

### B13. Workspace cleanup

- **Setup:** Default `media-processing.workspace.cleanup-enabled=true`.
- **Steps:** Run a successful local job; inspect `{java.io.tmpdir}/media-processing`.
- **Expect:** Per-job workspace gone after success. On download failure, workspace is also cleaned. With cleanup disabled, leftovers remain for debugging.

---

## C. Full chat path — video success

Use two logged-in group members. Confirm **both** receive the same websocket payloads (sender included).

### C1. Immediate pending message (does not wait for ffmpeg)

- **Steps:** Complete a video upload.
- **Expect:** Websocket `MessageResponse` almost immediately:
  - `messageType: VIDEO`
  - attachment `status: PROCESSING_PENDING`
  - `scanStatus: SCAN_PASSED` (if scan is on)
  - `width` / `height` / `durationMs` null
  - `posterUrl` / `thumbnailUrl` / `transcodedUrl` null
  - `contentUrl` / `downloadUrl` / `playbackUrl` still point at the **original** object
  - `videoSources` has a single `CANONICAL` entry for that original
  - UI shows “Processing media”

### C2. Ready message after worker + callback

- **Expect:** Second websocket (same message id):
  - `status: MEDIA_READY`
  - `durationMs`, `width`, `height` populated
  - `mimeType` becomes `video/mp4` after canonical switch
  - `posterUrl` and `thumbnailUrl` are the JPEG (`{stem}.thumbnail.jpg`)
  - `contentUrl`, `downloadUrl`, `playbackUrl`, `transcodedUrl` all point at the **canonical MP4**
  - `videoSources`: `CANONICAL` (full size) and, when generated, `MOBILE` (`{stem}.480p.mp4`, height 480)

### C3. History matches the ready payload

- **Steps:** Reload the group / fetch message history after `MEDIA_READY`.
- **Expect:** Same URLs, metadata, `videoSources`, and status as the ready websocket. No stale original-only URLs.

### C4. Original deleted only after URL switch (MinIO)

- **Steps:** After `MEDIA_READY` on a remux/re-encode (not reuse).
- **Expect:**
  - `{stem}.transcoded.mp4` exists
  - `{stem}.thumbnail.jpg` exists
  - original `.mov`/`.webm` **gone**
  - If reuse (already H.264+AAC MP4): original **kept**; no delete of the only object

### C5. Canonical file is chat-friendly

- **Steps:** Download `playbackUrl` / `downloadUrl`. `ffprobe` it.
- **Expect:** MP4, H.264, AAC (or no audio), plays in Chrome/Safari. Download of the chat file is that MP4, not the camera MOV (except reuse case, which already was MP4).

### C6. Chat-friendly MP4 reuse through chat upload

- **Steps:** Upload an H.264+AAC MP4.
- **Expect:** `MEDIA_READY`. MinIO still has the original key as canonical. No orphan `{stem}.transcoded.mp4`. Poster present. Original not deleted.

### C7. WebM through chat upload

- **Steps:** Upload WebM.
- **Expect:** Pending original webm URLs, then ready canonical MP4; original webm deleted; poster + optional 480p.

### C8. MP4 / MOV / WebM all accepted as video messages

- **Steps:** One upload of each allowed type (if the picker allows them).
- **Expect:** Each completes to `MEDIA_READY` (or fails only for corrupt files, not for container type).

### C9. Image is not queued to the video worker

- **Steps:** Send an image in the same environment.
- **Expect:** Attachment is `MEDIA_READY` immediately on publish (original object). No transcode/poster job. Images do not wait on the Micronaut worker (Phase 12 not done).

### C10. Audio/file messages

- **Steps:** Send audio or generic file if enabled.
- **Expect:** Not published as video processing jobs. Status `MEDIA_READY` with original object.

---

## D. Mobile 480p rendition

Skip rules (all of these skip 480p, canonical still `MEDIA_READY`): source/canonical height **≤ 480**; metadata incomplete; duration **< 15s**; canonical size **< 8,388,608 bytes**.

### D1. Generate 480p for a tall, long, large video

- **Fixture:** Like 24MB MOV ~32s ~1964p.
- **Expect:** `{stem}.480p.mp4` in MinIO. Ready payload `videoSources` has `MOBILE` with `height: 480`, proportional width, `mimeType: video/mp4`, smaller `sizeBytes` than canonical.

### D2. Skip 480p for already-small video

- **Fixture:** 480p or shorter/smaller than skip rules.
- **Expect:** Ready payload `videoSources` is **only** `CANONICAL`. No `.480p.mp4`. No failure.

### D3. Rendition failure must not fail canonical

- **How:** Hard to force without breaking ffmpeg for 480p only. If you can (e.g. kill disk during rendition, or temporarily point rendition ffmpeg at a bad binary while canonical already exists — usually not separable), watch logs.
- **Expect:** Worker logs rendition failure; callback still `MEDIA_READY` with canonical MP4; original still switched/deleted as usual.

### D4. Frontend prefers mobile on narrow viewport

- **Steps:** After a video that has `MOBILE`, open chat at width **≤ 768px** (devtools device mode). Inspect the `<video src>` (or network).
- **Expect:** Playback URL is the `MOBILE` source, not canonical. Poster still from `posterUrl`.

### D5. Frontend prefers canonical on wide/fast desktop

- **Steps:** Viewport **> 768px**, no data-saver / not 2G–3G (hard to fake `navigator.connection` in some browsers).
- **Expect:** `<video>` uses `playbackUrl` / `CANONICAL`.

### D6. Data saver / slow network (if the browser exposes it)

- **Steps:** Chrome DevTools → Network → Network throttling / “Data saver” if available, viewport can stay wide.
- **Expect:** Prefers `MOBILE` when `saveData` is true or `effectiveType` is `slow-2g` / `2g` / `3g`.

### D7. Old client fallback

- **Steps:** If you have a build that ignores `videoSources`, play the ready message.
- **Expect:** `playbackUrl` alone is enough. Missing mobile data must not break playback.

---

## E. Frontend contract (Phase 8)

### E1. Poster before play

- **Expect:** Video card uses `posterUrl` (fallback `thumbnailUrl`) before play. Duration/size copy visible when metadata exists.

### E2. Processing indicator

- **Expect:** `PROCESSING_PENDING` / `PROCESSING_IN_PROGRESS` → “Processing media”. `PROCESSING_FAILED` → “Processing failed” and original still playable/downloadable if URLs remain.

### E3. Do not invent URLs from raw keys

- **Expect:** After ready, player uses `playbackUrl` / `videoSources`, not a homemade transcode path.

---

## F. Failures, retries, auth

### F1. Transcode/metadata failure keeps the original

- **How:** Corrupt-but-non-empty video, or stop ffmpeg (rename `ffmpeg` on PATH) then upload.
- **Expect:** Attachment `PROCESSING_FAILED`. Original object **still in MinIO**. Chat message remains. Clients can still use original `contentUrl` if it was never switched. Worker reports failure via callback.

### F2. Missing source after publish

- **How:** Complete upload then delete the object from MinIO before the worker runs (race).
- **Expect:** `PROCESSING_FAILED`, `SOURCE_MISSING`. Message still in history.

### F3. Worker down

- **Setup:** `MEDIA_PROCESSING_ENABLED=true` but worker not running.
- **Steps:** Upload video.
- **Expect:** Message stays `PROCESSING_PENDING`. Chat otherwise works. When worker starts, job should process **if** the RabbitMQ message is still queued. If you restart RabbitMQ and lose the message, the video stays pending until Phase 13 (document as known gap).

### F4. Integration disabled

- **Setup:** `MEDIA_PROCESSING_ENABLED=false`. Worker may be running.
- **Steps:** Upload video.
- **Expect:** `PROCESSING_PENDING` forever (no job published). No callback. Original remains. Log: integration disabled.

### F5. Callback token mismatch

- **Setup:** Backend and worker tokens differ. Upload a video.
- **Expect:** Worker cannot apply results (401). Job may retry via RabbitMQ. Media stays pending or eventually fails depending on retries. Chat APIs still work.

### F6. Callback endpoint is not a user API

- **Steps:** `POST http://localhost:9010/api/internal/media-processing/results` without header, or wrong token.
- **Expect:** Unauthorized. With `MEDIA_PROCESSING_ENABLED=false`, endpoint is absent (`404`). Success is `204` only with valid token + valid body.

### F7. Duplicate callback after ready (lost HTTP / redelivery)

- **How:** After `MEDIA_READY`, re-publish the same job or leave the worker to redeliver.
- **Expect:** Backend stays `MEDIA_READY` and does **not** downgrade status or switch away from the canonical MP4. If the original is already gone, cleanup is a no-op. If `replaced_original_object_key` is still set (delete failed earlier), this callback **retries** that delete and must not delete the canonical object.

### F8. Same attachment processed twice (in-memory dedup)

- **How:** Local trigger with same `jobId` (B9). On the chat path, `jobId` is `media-{mediaId}`.
- **Expect:** Second delivery skipped while completed in this JVM. **Two worker processes do not share this store** (Phase 13). Do not treat cross-pod dedup as passing.

---

## G. Queue and payload contract

### G1. Job payload has no media bytes

- **Steps:** Inspect RabbitMQ message on `media.processing` / queue `media.processing.jobs` / routing key `media.processing.video` (management UI).
- **Expect:** JSON identifiers only: `jobId`, `messageId`, `mediaId`, `messageType`, `storageProvider`, `bucket`, `objectKey`, `requestedMimeType`, `processingTargets`. `jobId` is `media-{mediaId}`. Targets include `METADATA`, `THUMBNAIL`, `TRANSCODE`. No file bytes.

### G2. Only video is published

- **Steps:** Upload image + video.
- **Expect:** Queue gets a job for the video only.

---

## H. MinIO HTTPS / local-development (uploader)

Applies when constructing the worker MinIO **uploader**. Local HTTP is allowed only with `media-processing.storage.local-development=true` (default in `application.properties` via env).

### H1. Local HTTP allowed

- **Setup:** `MEDIA_PROCESSING_STORAGE_LOCAL_DEVELOPMENT=true`, endpoint `http://localhost:9000`.
- **Expect:** Worker starts; uploads (poster/transcode) succeed.

### H2. Production HTTP rejected at startup

- **Setup:** `MEDIA_PROCESSING_STORAGE_LOCAL_DEVELOPMENT=false` and endpoint `http://minio.example.com` (or localhost HTTP).
- **Expect:** Worker fails while creating `MinioObjectStorageUploader` **before** using access/secret keys. Error mentions HTTPS / local-development.

### H3. HTTPS without local-dev

- **Setup:** Flag false, `https://…` endpoint (even if TLS is a stub you cannot actually upload to).
- **Expect:** Client **constructs**. Connection errors later are separate from the scheme check.

---

## I. Allowlist at chat upload

The service doc requires rejecting non-MP4/MOV/WebM using **server-detected** type, not only the client MIME.

### I1. Client lies about MIME

- **Steps:** If you can upload a `.avi` (or rename AVI to `.mp4`) through prepare/complete.
- **Expect (spec):** Upload validation rejects it. **If it publishes**, worker should still fail transcode (`UNSUPPORTED_VIDEO_FORMAT`) and leave original; record that as a product gap vs upload-time reject.

---

## J. Negative / regression checks

### J1. Worker does not write the chat database

- **Steps:** After success, confirm DB `message_media` updates only happen when backend logs the callback. Worker logs should show HTTP callback, not JDBC to chat Postgres.

### J2. Empty `pendingTargets` on finished jobs

- **Expect:** Ready callbacks succeed (`204`). A finished job must not 400 on empty collections (this was a Serde/`@NotNull` bug).

### J3. Bounded concurrency

- **Steps:** Upload several large videos at once (`consumer-concurrency` default `1`).
- **Expect:** Jobs process sequentially (or with the configured concurrency), not unbounded ffmpeg processes.

### J4. Interrupt / stop worker mid-ffmpeg

- **Steps:** Start a long transcode; stop the worker JVM.
- **Expect:** ffmpeg child should not keep running indefinitely (transcoder interrupt cleanup). Check `ps` for leftover `ffmpeg`. Chat message may stay pending until retry.

---

## Result log

Copy rows as you test:

| ID | Result | Notes (message id, object keys, bugs) |
| --- | --- | --- |
| A1 | | |
| A2 | | |
| A3 | | |
| B1 | | |
| B2 | | |
| B3 | | |
| B4 | | |
| B5 | | |
| B6 | | |
| B7 | | |
| B8 | | |
| B9 | | |
| B10 | | |
| B11 | | |
| B12 | | |
| B13 | | |
| C1 | | |
| C2 | | |
| C3 | | |
| C4 | | |
| C5 | | |
| C6 | | |
| C7 | | |
| C8 | | |
| C9 | | |
| C10 | | |
| D1 | | |
| D2 | | |
| D3 | | |
| D4 | | |
| D5 | | |
| D6 | | |
| D7 | | |
| E1 | | |
| E2 | | |
| E3 | | |
| F1 | | |
| F2 | | |
| F3 | | |
| F4 | | |
| F5 | | |
| F6 | | |
| F7 | | |
| F8 | | |
| G1 | | |
| G2 | | |
| H1 | | |
| H2 | | |
| H3 | | |
| I1 | | |
| J1 | | |
| J2 | | |
| J3 | | |
| J4 | | |

## Suggested order

1. A1, B1, H1 — confirm processes and local HTTP.
2. B2–B5, B7–B8, D1–D2 — worker-only derivatives (fastest signal).
3. Enable callback + RabbitMQ; C1–C8, D4–D5, E1–E2 — real chat UX.
4. F1, F3–F7, I1, J4 — failures and ops.
5. Leave B11–B12 and H2 for last (they need config restarts).

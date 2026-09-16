# Search Feature

## Intro

This document designs message search for the chat app. The feature should let users search messages across every group they belong to, or search only inside one selected group.

The first version should search text messages efficiently and support Vietnamese queries with or without diacritics, plus case-insensitive matching. Later versions should also search text extracted by `media-processing-service` from images, video frames, and spoken audio in videos.

Current state:

- `messages.content` stores message text as a nullable `VARCHAR(1000)`.
- There is no search-specific index for `messages.content`.
- A query like `LIKE '%search_text%'` would require scanning many message rows and becomes inefficient as message volume grows.

## Functional Requirements

- Support global search.
- Global search returns matching messages from all groups that the current user is allowed to access.
- Support local group search.
- Local search returns matching messages only from one specific group.
- Frontend should expose two search boxes:
  - one global search box outside a specific group context
  - one local search box inside the selected group conversation
- Search must be case-insensitive.
- Search must support Vietnamese text with and without diacritics.
- Example: query `ban dang lam gi the` should match:
  - `bạn đang làm gì thế`
  - `bạn dang làm gi the`
  - other mixed-diacritic variants with the same normalized text
- Search results must not include messages from groups the user is not a member of.
- Search results should not expose soft-deleted message content.
- Search should return enough context for the UI to navigate to the message inside the group conversation.
- Search should support pagination or cursor-based loading.
- Search should allow filtering by group for local search.
- Search should be ready to expand to media-derived text:
  - OCR text extracted from images
  - transcript text extracted from video or audio speech
  - OCR text extracted from video frames
- Media-derived text should be produced by the Micronaut `media-processing-service` described in `docs/29_MEDIA_PROCESSING_SERVICE.md`.
- Searching text inside images and videos is not required for MVP.

## Non-Functional Requirements

- Avoid full table scans on `messages.content` for normal search traffic.
- Keep global search bounded by the user's group membership.
- Keep search result latency acceptable for chat UX:
  - target under 300 ms for common local searches
  - target under 700 ms for common global searches at moderate scale
- Search indexing should not block sending a message.
- Search results can be eventually consistent if a dedicated index is used.
- Newly sent text messages should usually become searchable within a few seconds.
- The design should preserve a future path to switch database engines, even if the first implementation uses PostgreSQL-specific optimizations.
- The design should avoid sending full message bodies through RabbitMQ unless RabbitMQ is intentionally chosen as a search-indexing queue later.
- Search indexing and media text extraction must be retryable and idempotent.
- Search indexing should be observable:
  - index lag
  - failed indexing jobs
  - OCR or speech-to-text failures
  - search latency
- Search must respect existing authorization rules even if the index contains messages from many groups.
- Search must distinguish an unavailable search index from a valid empty result set:
  - Prefer a bounded database fallback where the product supports it (for example MVP `search_content` queries).
  - Otherwise return a typed service-unavailable error such as HTTP `503`, never a successful empty page.
  - Index outages must never produce an empty successful search response that could be mistaken for "no matches".
- Search should not require Kafka for MVP.

## Use Cases

1. User searches globally from the sidebar or top-level search screen.
   - The backend scopes candidates to groups the user can access, then finalizes authorization via `GroupAuthorizationService`.
   - The UI shows matching message snippets grouped or labeled by group.

2. User searches inside a selected group.
   - The backend scopes to that group and calls `GroupAuthorizationService` for the final read permission decision.
   - The UI lets the user jump to the message in the conversation.

3. User searches Vietnamese without diacritics.
   - Query `ban dang lam gi the` matches stored content such as `bạn đang làm gì thế`.
   - Query matching is case-insensitive.

4. User searches a word that appears in an image.
   - Not MVP.
   - `media-processing-service` extracts text from image attachments and stores/indexes it as searchable text linked to the original message.

5. User searches a phrase spoken in a video.
   - Not MVP.
   - `media-processing-service` creates transcript segments linked to the message and media attachment.

6. User searches text visible in a video frame.
   - Not MVP.
   - `media-processing-service` samples frames, extracts visible text, and indexes it with timestamps if needed.

## Possible Solutions

### 1. How should text search be implemented?

#### 1.1. B-tree Index on `messages.content`

- How it works:
  - Add a normal B-tree index on `messages.content`.
  - Query the column with equality, prefix matching, or range-like conditions.
- Pros:
  - Simple.
  - Portable across most relational databases.
  - Useful for exact match or prefix search such as `content LIKE 'hello%'`.
- Cons:
  - Does not help much for `LIKE '%hello%'`.
  - Does not solve Vietnamese accent-insensitive matching by itself.
  - Does not solve ranking, tokenization, typo tolerance, or fuzzy matching.
  - Large text columns can make the index less useful and more expensive.
- Recommendation for our problem: No.
- When I'd use it:
  - Exact or prefix-only search on a short normalized field.

#### 1.2. Lowercase and Accent-Folded Shadow Column With Simple `LIKE`

- How it works:
  - Store a normalized version of searchable message text, for example `search_content`.
  - Normalization happens in application code through the shared Vietnamese normalizer (see section 2.1).
  - Indexing and query parsing use the same normalizer.
  - Create and edit paths maintain `search_content` in the **same transaction** as `messages.content`:
    - On create: persist `content` and `search_content` together.
    - On edit: update `content` and recompute `search_content` together.
    - Soft-delete does not need to clear `search_content`, but every search query must exclude soft-deleted rows.
  - If normalization fails for a create or edit:
    - Fail the write (do not commit `content` without a matching usable `search_content`), **or**
    - Persist a documented sentinel such as `NULL` `search_content` and exclude that row from search until a successful re-normalization/backfill.
    - Do not leave an inconsistent searchable string that does not match the shared normalizer contract.
  - Existing rows need an idempotent migration/backfill:
    - Recompute `search_content` from current non-soft-deleted `messages.content`.
    - Safe to re-run; already-normalized rows can be overwritten with the same result.
    - Soft-deleted rows may be skipped or filled, but remain excluded by the search predicate.
  - Query contract for MVP:
    ```sql
    WHERE deleted_at IS NULL
      AND search_content LIKE '%' || :escapedNormalizedQuery || '%'
    ```
    Escape `%` and `_` in the normalized query so matching is literal substring, not SQL wildcard matching.
- Pros:
  - Database portable.
  - Easy to understand.
  - Correctly handles case-insensitive and accent-insensitive matching if normalization is consistent.
  - Same-transaction maintenance keeps `content` and `search_content` consistent for creates and edits.
- Cons:
  - Still inefficient for leading-wildcard substring search without an additional index strategy.
  - Duplicates message text in the database.
  - Does not provide strong ranking.
  - Requires careful create/edit/backfill and soft-delete query discipline.
- Recommendation for our problem: No as the only search solution.
- When I'd use it:
  - Very small datasets.
  - Temporary implementation before adding a real index.
  - As a normalization source for a more advanced indexing strategy.

#### 1.3. PostgreSQL `unaccent` Plus `pg_trgm`

- How it works:
  - PostgreSQL `unaccent` removes diacritics.
  - PostgreSQL `pg_trgm` creates trigram indexes that can speed up substring and fuzzy text matching.
  - Queries normalize both stored content and search text before matching.
  - Common production shape:
    - normalize in application code into a `search_content` column
    - add a PostgreSQL GIN trigram index on `search_content`
- Pros:
  - Very good fit for chat substring search.
  - Handles `LIKE '%query%'` much better than B-tree.
  - Supports case-insensitive and accent-insensitive matching.
  - Lower operational complexity than Elasticsearch.
  - Good MVP path because the app already runs PostgreSQL in Docker.
- Cons:
  - PostgreSQL-specific.
  - Not portable to MySQL, SQL Server, or other engines without rework.
  - Relevance ranking is limited compared with a dedicated search engine.
  - Needs careful migration and backfill for existing messages.
- Recommendation for our problem: Yes for a PostgreSQL-first MVP.
- Portability note:
  - This does not prevent a future database switch, but search would need a new adapter or a dedicated search engine before switching away from PostgreSQL.

#### 1.4. Database Full-Text Search

- How it works:
  - Use the database's full-text search features to tokenize message content and query terms.
  - PostgreSQL uses `tsvector` and `tsquery`.
  - MySQL, SQL Server, and other databases have different full-text search implementations.
- Pros:
  - Better semantic text search than raw `LIKE`.
  - Can rank results by relevance.
  - Efficient for word-based search.
  - Can support language-specific tokenization and stop words depending on the database.
- Cons:
  - Full-text search is usually token-based, not arbitrary substring based.
  - Vietnamese support may need validation. Tokenization, accents, and mixed diacritics can vary by engine.
  - Cross-database behavior is not consistent.
  - PostgreSQL full-text search works best for lexeme-based search, but chat users often expect partial phrase and substring matching.
- Recommendation for our problem: Maybe later, not the best MVP default.
- When I'd use it:
  - Search becomes more document-like, with ranking, phrase search, stemming, and advanced filters.
  - We accept database-specific search behavior.

#### 1.5. Portable N-gram Search Table in the Main Database

- How it works:
  - Application code normalizes content.
  - It then writes generated n-grams into a separate table such as `message_search_ngrams`.
  - Search normalizes the query, generates query n-grams, and joins against the n-gram table to find candidate messages.
- Pros:
  - More portable than PostgreSQL `pg_trgm`.
  - Can support accent-insensitive and case-insensitive substring search.
  - Keeps all data in the main database.
- Cons:
  - More custom code.
  - Significant write amplification.
  - More complex ranking and deduplication.
  - Table size can grow quickly.
  - Harder to tune than a native trigram index.
- Recommendation for our problem: No for MVP.
- When I'd use it:
  - Database portability is mandatory from day one.
  - A dedicated search engine is not allowed.

#### 1.6. Dedicated Search Engine

- How it works:
  - Store canonical messages in PostgreSQL.
  - Maintain a separate search index in Elasticsearch, OpenSearch, Meilisearch, Typesense, or a similar engine.
  - Search queries go to the search engine.
  - Message writes publish or persist an indexing event after the database transaction commits.
- Pros:
  - Best long-term search capability.
  - Database engine independent.
  - Better relevance scoring, highlighting, typo tolerance, filtering, and ranking.
  - Clean place to index media-derived text from OCR and speech-to-text.
  - Avoids putting search-specific complexity into the primary database.
- Cons:
  - More infrastructure.
  - Eventual consistency.
  - Requires index backfill, reindexing, monitoring, retry, and dead-letter handling.
  - Authorization must still be enforced carefully.
  - Elasticsearch/OpenSearch can be operationally heavy for a small app.
- Recommendation for our problem: Yes for the higher-scale path, but not required for MVP.
- Candidate engines:
  - Elasticsearch or OpenSearch: powerful and mature, best for complex search and large scale.
  - Meilisearch: simpler operations, great developer experience, good typo tolerance.
  - Typesense: simple and fast, good for structured filters.

### 2. How should Vietnamese accent-insensitive search be handled?

#### 2.1. Normalize in Application Code

- How it works:
  - Add one shared normalizer used by both indexing (`search_content` / search documents) and query parsing.
  - Vietnamese normalization contract:
    1. Unicode form: normalize input to NFC before further processing.
    2. Case folding: lowercase using Unicode-aware case folding after NFC.
    3. Explicit `đ` / `Đ` conversion: map `đ` and `Đ` to `d` (do not rely only on generic ASCII folding, because `đ` is not a combining-diacritic form of `d`).
    4. Diacritic removal: strip remaining Vietnamese combining marks / equivalent accented characters so accented and unaccented queries match.
    5. Whitespace collapsing: trim leading/trailing whitespace and collapse internal runs of whitespace to a single ASCII space.
    6. Punctuation handling: MVP keeps alphanumeric and spaces after folding; strip or normalize common punctuation so `làm gì?` and `lam gi` can match. Document the exact punctuation set in the normalizer implementation.
    7. Length limits: reject or truncate normalized query/`search_content` according to the same max length used by searchable message text (aligned with `messages.content` / API `q` max). Empty normalized output after collapsing is not a searchable query.
  - Example:
    - raw text: `Bạn Đang Làm Gì Thế`
    - normalized text: `ban dang lam gi the`
  - Indexing and query parsing must call the same normalizer class/method so stored and queried forms cannot drift.
  - Required tests for the normalizer:
    - NFC vs NFD composed/decomposed Unicode for the same Vietnamese phrase
    - mixed diacritics vs fully unaccented text
    - explicit `đ` / `Đ` → `d`
    - uppercase and mixed-case input
    - leading/trailing/multiple whitespace variants
    - punctuation variants covered by the MVP punctuation rule
- Pros:
  - Works across databases and search engines.
  - Testable in Java.
  - Keeps behavior consistent between PostgreSQL MVP and a future search engine.
- Cons:
  - Needs careful Vietnamese character mapping.
  - Must handle Unicode normalization consistently.
  - Existing messages need a backfill.
- Recommendation for our problem: Yes.

#### 2.2. Rely on Database Functions Such as PostgreSQL `unaccent`

- How it works:
  - Use `unaccent(lower(content))` or an indexed generated/search column based on unaccented text.
- Pros:
  - Less application code.
  - Good fit if PostgreSQL is guaranteed.
- Cons:
  - PostgreSQL-specific.
  - Can be harder to keep portable.
  - Expression indexes around `unaccent` need care because function volatility can affect index usage.
- Recommendation for our problem: No as the only normalization strategy.
- When I'd use it:
  - PostgreSQL is a committed long-term dependency.

#### 2.3. Search Engine Analyzer

- How it works:
  - Configure the search engine analyzer to lowercase and ASCII-fold Vietnamese text.
  - Index both raw and normalized forms if needed.
- Pros:
  - Strong fit for dedicated search engines.
  - Supports ranking and highlighting better than a database-only approach.
- Cons:
  - Engine-specific configuration.
  - Still useful to keep application-level normalization tests for predictable behavior.
- Recommendation for our problem: Yes when moving to a dedicated search engine.

### 3. How should global search enforce authorization?

Local and global search must call the centralized permission service (`GroupAuthorizationService`) for the final authorization decision, including actor membership/permission checks and any target-role rules that apply to the searched content. Membership joins and allowed-group-ID filters are **query-scoping mechanisms only**. They narrow the candidate set; they are not a replacement for the centralized authorization check.

#### 3.1. Join Through Current Membership Tables at Query Time

- How it works:
  - Local search scopes the SQL/query by `group_id`, then calls `GroupAuthorizationService` (for example `requirePermission(..., READ_MESSAGES)` or the active-membership equivalent used by message history) before returning results.
  - Global search joins messages to current group membership to scope candidates to groups the actor belongs to, then still applies the centralized permission decision for the actor (and target-role rules when the searched action or hydrated content requires them).
  - Banned or non-member actors must be rejected by the permission service even if a stale join somehow included a row.
- Pros:
  - Scopes the candidate set using the membership source of truth.
  - Final allow/deny stays in one authorization gate already used by chat APIs.
  - No stale membership permissions treated as the final decision inside the search index.
- Cons:
  - Can be slower for global search if the query scans many messages first.
  - Requires good indexes on membership and message group/timestamp fields.
  - Easy to misuse if developers treat the join alone as authorization.
- Recommendation for our problem: Yes for database-backed MVP, with membership joins as scoping only and `GroupAuthorizationService` as the final gate.

#### 3.2. Filter by Allowed Group IDs in the Search Query

- How it works:
  - Backend loads the user's currently allowed group IDs after authorization helpers resolve membership/permissions.
  - Backend sends `groupId IN (...)` as a **scope filter** to the database or search engine.
  - Before returning hydrated results, the backend still relies on the centralized permission service for the final decision (local: authorize that group; global: ensure each result group remains allowed and apply actor/target-role rules as required).
  - Membership changes invalidate previous scopes; do not trust a previously cached group-ID list as the final authz decision.
- Pros:
  - Works for both database search and dedicated search engines.
  - Keeps the final authorization decision in backend code via `GroupAuthorizationService`.
  - Useful as a pre-filter so the index never becomes the authz system of record.
- Cons:
  - Large group lists may create large filters.
  - Must refresh allowed group IDs carefully after membership/ban/role changes.
  - Dangerous if treated as a substitute for the permission service.
- Recommendation for our problem: Yes for the search engine path, as scoping only, with centralized permission checks retained.

#### 3.3. Store User Access Lists Inside the Search Index

- How it works:
  - Index each message with the users who can read it.
  - Search filters by the current user ID.
- Pros:
  - Fast search-time filtering.
- Cons:
  - Bad fit for group chat.
  - High reindexing cost when membership changes.
  - Easy to leak data if the index is stale.
  - Bypasses the centralized permission service and target-role rules.
- Recommendation for our problem: No. Reject user access lists in the search index; keep authorization in `GroupAuthorizationService` and use membership only for query scoping.

### 4. How should the search index be synced?

#### 4.1. Synchronous Index Update Inside Message Send

- How it works:
  - When a message is saved, the backend immediately writes to the search index before returning success.
- Pros:
  - Simple.
  - Search is immediately consistent.
- Cons:
  - Message sending depends on search availability.
  - Search outages break chat writes.
  - Adds latency to the message send path.
- Recommendation for our problem: No.

#### 4.2. After-Commit Index Event From `chat-app-backend`

- How it works:
  - Inside the message write `@Transactional` method, persist the message **and** a durable `SearchOutbox` (or equivalent outbox) record in the **same database transaction**.
  - Do **not** publish to RabbitMQ, call `@Async`, or start other asynchronous indexing work while that transaction is still active.
  - Use an after-commit hook only to trigger outbox publication or wake a poller after the transaction has successfully committed.
  - A worker then consumes durable outbox work and updates the search index.
- Pros:
  - Does not block message writes on external indexing availability.
  - Avoids publishing events before the message row is committed.
  - Survives process crash after commit when paired with the durable outbox row.
  - Can start inside the existing backend process.
  - Can later be extracted into a separate service.
- Cons:
  - Eventually consistent.
  - Needs retry and dead-letter handling.
  - Requires outbox cleanup and monitoring.
- Recommendation for our problem: Yes.
- Note: This option and section 4.3 are complementary. 4.2 describes the after-commit trigger boundary; 4.3 describes the durable outbox row that must be written with the message.

#### 4.3. Transactional Outbox Table

- How it works:
  - Save the message and an outbox row in the same database transaction.
  - After commit, a publisher/poller (triggered by after-commit or a scheduled worker) reads unsent outbox rows and sends them to a queue or directly indexes them.
  - Asynchronous work and RabbitMQ publishing happen only after the write transaction has committed.
- Pros:
  - Strong reliability.
  - Avoids losing indexing events if the process crashes after commit.
  - Works well before introducing Kafka.
- Cons:
  - More tables and worker logic.
  - Requires cleanup and monitoring.
- Recommendation for our problem: Yes if we build a dedicated search index or media extraction pipeline; also the preferred durability pattern underneath the after-commit trigger in 4.2.

#### 4.4. Change Data Capture

- How it works:
  - Use Debezium or a similar CDC tool to read committed database changes and feed the search index.
- Pros:
  - Very reliable at scale.
  - Decouples indexing from application write code.
  - Common with Kafka.
- Cons:
  - Operationally complex.
  - Usually overkill for the first search implementation.
  - Often introduces Kafka or another durable log.
- Recommendation for our problem: No for MVP.
- When I'd use it:
  - High message volume.
  - Multiple downstream consumers.
  - Dedicated platform ownership for Kafka or CDC.

### 5. Do we need a new service, RabbitMQ, or Kafka?

#### 5.1. Keep Search Orchestration Inside `chat-app-backend` First

- How it works:
  - Add search APIs, normalization, and indexing orchestration inside the existing Spring Boot app.
  - If using PostgreSQL `pg_trgm`, queries stay entirely inside the backend and database.
  - If using a search engine later, indexing workers can initially run as backend-managed scheduled/background jobs.
- Pros:
  - Lowest operational overhead.
  - Fits current app size.
  - Avoids premature service boundaries.
- Cons:
  - Backend process owns more responsibilities.
  - Heavy indexing or OCR work may compete with request handling unless isolated carefully.
- Recommendation for our problem: Yes for MVP.

#### 5.2. Add a Separate Search Indexer Service

- How it works:
  - `chat-app-backend` writes messages and emits durable indexing events.
  - A separate service consumes events and updates the search engine.
- Pros:
  - Independent scaling.
  - Cleaner ownership once search grows.
  - Better isolation from chat APIs.
- Cons:
  - More deployment and monitoring work.
  - Requires a reliable event pipeline.
- Recommendation for our problem: Later, when using a dedicated search engine or heavy media extraction.

#### 5.3. Use RabbitMQ as the Indexing Queue

- How it works:
  - Backend publishes message-index events to RabbitMQ after commit.
  - Indexing worker consumes events and updates the search index.
- Pros:
  - RabbitMQ already exists in this project.
  - Good enough for background jobs with retries and dead-letter queues.
  - Simpler than Kafka.
- Cons:
  - Current RabbitMQ usage is primarily real-time cross-instance fan-out.
  - Need separate durable queues, retry policy, and idempotent consumers for indexing.
  - RabbitMQ is not a replayable event log like Kafka.
- Recommendation for our problem: Maybe for phase 2, not required for PostgreSQL-only MVP.

#### 5.4. Use Kafka

- How it works:
  - Backend writes events to Kafka.
  - Search indexer, OCR worker, analytics, and other consumers read from Kafka topics.
- Pros:
  - Durable event log.
  - Strong replay and backfill story.
  - Good when many services need the same message events.
- Cons:
  - Operationally heavy.
  - Not currently part of this app.
  - Overkill for a first search feature.
- Recommendation for our problem: No for MVP.
- When I'd use it:
  - High traffic.
  - Many downstream consumers.
  - Need replayable event streams for search, analytics, moderation, and ML pipelines.

### 6. How should image and video text search work?

#### 6.1. OCR for Images

- How it works:
  - When an image attachment is accepted, an OCR worker extracts visible text.
  - Extracted text is stored as media-derived searchable text linked to the message and attachment.
  - The search index includes both message text and OCR text.
- Pros:
  - Natural extension of message search.
  - Users can find images by visible text.
- Cons:
  - OCR can be slow and imperfect.
  - Vietnamese OCR quality depends heavily on engine/model choice and image quality.
  - Requires background processing, retries, and status tracking.
- Recommendation for our problem: Yes after text search MVP.

#### 6.2. Build OCR From Scratch

- How it works:
  - Train and operate our own OCR models.
- Pros:
  - Maximum control.
  - Can optimize for app-specific media over time.
- Cons:
  - Very high engineering and ML cost.
  - Requires labeled data, model evaluation, deployment, and monitoring.
  - Not justified for this app's initial needs.
- Recommendation for our problem: No.

#### 6.3. Use Existing OCR Tools or Managed OCR

- How it works:
  - Use tools such as Tesseract/EasyOCR for self-hosted OCR, or managed services such as Google Cloud Vision, AWS Textract/Rekognition, or Azure AI Vision.
- Pros:
  - Much faster to ship.
  - Better accuracy than building from scratch.
  - Managed services reduce operations.
- Cons:
  - Self-hosted OCR still needs CPU/GPU capacity and tuning.
  - Managed OCR adds cost and vendor dependency.
  - Media privacy and data retention must be reviewed.
- Recommendation for our problem: Yes.
- Suggested path:
  - Start with a managed OCR service if product value is high and traffic is low.
  - Consider self-hosted OCR later if cost or privacy requires it.

#### 6.4. Speech-to-Text for Video or Audio

- How it works:
  - Extract audio from video/audio attachments.
  - Send audio to a speech-to-text engine.
  - Store transcript segments linked to the message and timestamps.
  - Search transcript text like normal message text.
- Pros:
  - Supports searching for spoken phrases.
  - Timestamped results can jump to the relevant video time.
- Cons:
  - More expensive than text search.
  - Accuracy varies by audio quality, speaker accent, and background noise.
  - Needs async processing and failure states.
- Recommendation for our problem: Later.

#### 6.5. Video OCR

- How it works:
  - Sample video frames at intervals or detect scene changes.
  - Run OCR on selected frames.
  - Store extracted text with message ID, attachment ID, and timestamp.
- Pros:
  - Supports finding text shown in screen recordings, slides, signs, and captions.
- Cons:
  - Can be expensive.
  - Naive frame sampling misses short-lived text or wastes processing on duplicate frames.
  - Needs deduplication and confidence thresholds.
- Recommendation for our problem: Future higher-scale feature.

## High Level Architecture/Design

### Recommended MVP Flow

```mermaid
flowchart LR
    A[React global search box] --> B[Search API]
    C[React group search box] --> B
    B --> D[Auth via GroupAuthorizationService + membership scope]
    D --> E[Normalize query]
    E --> F[(PostgreSQL messages + search_content)]
    F --> G[Search result DTOs]
    G --> H[React results panel]
```

Index unavailable: bounded DB fallback where supported; otherwise typed 503 — never an empty successful page.

### Future Dedicated Search Flow

```mermaid
flowchart LR
    A[Message send / edit / delete] --> B[(PostgreSQL)]
    B --> C[Transactional outbox]
    C --> D[Indexing worker]
    D --> E[(Search engine)]
    F[media-processing-service\nOCR / speech-to-text] --> M[(media_extracted_text)]
    M --> C
    G[Search API] --> H[GroupAuthorizationService + allowed group ID scope]
    H --> E
    E --> I[Message IDs + highlights]
    I --> J[Backend hydrates safe results]
```

### Core Entities/Models

- `Message`
  - Source of truth for chat messages.
  - Existing `content` stores text for text messages.
  - Search should ignore or carefully handle system-message content because system messages store stable event names instead of final human-readable text.

- `MessageSearchDocument` or `message_search_documents`
  - Future abstraction representing searchable text for one message.
  - Can include normalized message text and media-derived text.
  - Useful if we move to a dedicated search engine or want separate DB search state.

- `MessageMedia`
  - Source of media attachment metadata.
  - Future OCR and speech-to-text output should link to media attachments and messages.

- `MediaExtractedText`
  - Future table for OCR and transcript results produced by `media-processing-service`.
  - Suggested fields:
    - `id`
    - `message_id`
    - `media_id`
    - `source_type`: `IMAGE_OCR`, `VIDEO_OCR`, `SPEECH_TO_TEXT`
    - `raw_text`
    - `normalized_text`
    - `language`
    - `confidence`
    - `start_ms`
    - `end_ms`
    - `processor_name`
    - `processor_version`
    - `status`: at least `PENDING`, `READY`, `FAILED`, `TOMBSTONED` / suppressed
    - `failure_reason` (nullable)
    - `tombstoned_at` (nullable)
    - `tombstone_reason` (nullable): e.g. `MESSAGE_SOFT_DELETED`, `MEDIA_REPLACED`, `ACCESS_RESTRICTED`, `EXTRACTION_FAILED`
    - `created_at`
    - `updated_at`
  - Lifecycle / retention rules:
    - **Ready text is searchable** only while the source message is not soft-deleted, the media attachment is still the current accessible attachment for that message, and the actor would be allowed to read the source message/media.
    - **Message soft-delete:** tombstone or hard-delete linked `MediaExtractedText` rows and remove/suppress corresponding documents in every search index (`search_content` media fields, dedicated search engine docs, n-gram/media side indexes). Media-derived text must no longer match after the message becomes inaccessible.
    - **Media replaced or removed:** tombstone extracted text for the old `media_id`, delete/suppress old index documents, and only reindex after a successful new extraction for the replacement media.
    - **Access-restricted:** if bans, membership loss, archive, or other authz changes make the source inaccessible to a user, search APIs must not return media-derived hits for that user. Do not rely on stale index ACL lists (see section 3.3 rejection); scope by membership and finalize with `GroupAuthorizationService`. Shared index documents for restricted sources should be suppressed or filtered at query/hydration time so inaccessible content is not leaked.
    - **Failed extraction:** keep `status = FAILED` (or equivalent) with reason; do **not** index failed rows as searchable text. Retries may overwrite the same logical extraction idempotently. Failed rows are not search hits.
    - **Cleanup/retention:** tombstoned or orphaned extracted-text rows should be cleaned up by a retention job after a configured window, or deleted immediately when product prefers strict purge. Cleanup must also delete corresponding search-index documents so tombstoned media text cannot resurface.
  - Deletion/suppression rule for every search index:
    - When the source message/media becomes inaccessible or extraction is failed/tombstoned, remove or suppress the media-derived searchable fields/documents so that text is no longer searchable.

- `SearchOutbox`
  - Future durable event table for indexing and media-derived text updates.
  - Useful before adopting Kafka.

### API Draft

Shared request rules for Global Search and Local Group Search:

- `q`:
  - Required for a search request.
  - Server-enforced minimum and maximum length after trim (exact bounds TBD in Open Questions / config; document chosen constants in the API).
  - Queries below the minimum or above the maximum are rejected with `400`, not silently truncated into surprising matches.
  - Normalize with the shared Vietnamese normalizer, then escape `%` and `_` so `LIKE` / equivalent substring matching is literal.
- `limit`:
  - Optional page size with server-enforced minimum and maximum bounds (for example default `20`, min `1`, max `50`; exact constants TBD but must be enforced server-side).
  - Values outside bounds are rejected or clamped only if the API explicitly documents clamping; prefer reject with `400` for clarity.
- `cursor`:
  - Opaque to clients. Clients must not parse or construct cursors.
  - Bound to the normalized query, search scope (global vs local), `groupId` for local search, active filters, and sort order.
  - A cursor from a different `q` / scope / group / filter / sort must be rejected with `400`.
- Sort / pagination stability:
  - Primary order: newest first.
  - Stable unique tuple: `(timestamp DESC, messageId DESC)`.
  - When timestamps tie, `messageId DESC` breaks ties so pages neither duplicate nor skip rows across cursor fetches.
- Index unavailable:
  - Use bounded DB fallback where supported; otherwise return typed `503` (or equivalent). Never return `200` with an empty page solely because the index is down.

#### Global Search

```http
GET /api/search/messages?q=ban%20dang%20lam%20gi%20the&cursor=...&limit=20
```

Authorization:

- Scope candidate groups via current membership / allowed group IDs.
- Finalize with `GroupAuthorizationService` (actor permission to read messages; apply target-role rules when hydrating or exposing moderated content requires them).

Response shape:

```json
{
  "items": [
    {
      "messageId": 123,
      "groupId": 45,
      "groupName": "Study Group",
      "senderId": 7,
      "senderUsername": "an",
      "messageType": "TEXT",
      "snippet": "bạn đang làm gì thế",
      "matchedSource": "MESSAGE_CONTENT",
      "timestamp": "2026-08-07T00:00:00"
    }
  ],
  "nextCursor": "..."
}
```

`nextCursor` encodes the opaque continuation key for the next page under the same normalized query, scope, filters, and `(timestamp DESC, messageId DESC)` order. Absence of `nextCursor` means there is no further page.

#### Local Group Search

```http
GET /api/groups/{groupId}/search/messages?q=ban%20dang%20lam%20gi%20the&cursor=...&limit=20
```

Authorization:

- Scope by `{groupId}`.
- Call `GroupAuthorizationService` for the final decision that the actor may read messages in that group (including ban/membership/role rules). Membership path parameters alone are not sufficient.

Response can match the global response shape, but `groupId` is already implied by the route. Cursor binding includes this `groupId`.

#### Future Search Filters

Possible query parameters:

- `fromUserId`
- `messageType`
- `from`
- `to`
- `source`: `MESSAGE_CONTENT`, `IMAGE_OCR`, `VIDEO_OCR`, `SPEECH_TO_TEXT`
- `mediaId` or attachment id when navigating directly to a media match

### Frontend UX Draft

- Global search box:
  - Place in the sidebar, header, or a dedicated search screen.
  - Searches across all groups the user belongs to.
  - Results should show group name, sender, snippet, and timestamp.
  - Selecting a result opens the group and scrolls/jumps to the message.

- Local group search box:
  - Place inside the active group conversation UI.
  - Searches only the selected group.
  - Results can be shown in a side panel, dropdown, or search result list.
  - Selecting a result jumps to the message in the current group.

- Query behavior:
  - Debounce typing.
  - Do not search empty or below-minimum queries; show validation guidance instead.
  - Show loading, no-results, and error states.
  - Distinguish "no matches" from search-unavailable (`503` / error banner). Never treat an index outage as an empty result list.
  - Keep local and global query state separate.

## Recommendation

Recommended phased path:

1. MVP: implement database-backed text search using application-level normalization plus PostgreSQL `pg_trgm` on a normalized `search_content` column.
2. Keep search APIs inside `chat-app-backend`.
3. Enforce final authorization through `GroupAuthorizationService` (actor and target-role rules). Treat membership joins / allowed group IDs as query scoping only; do not store user access lists in the search index.
4. Do not use Kafka for MVP.
5. Do not create a new search service for MVP.
6. Write message + durable `SearchOutbox` in the same transaction; use after-commit only to trigger outbox publication/polling. Never publish RabbitMQ or start async indexing while the write `@Transactional` method is active.
7. Use `media-processing-service` as the owner of OCR/transcript generation; search should consume stored `media_extracted_text` rows or media-text-extracted events, not run OCR inside request handlers. Tombstone/suppress media-derived text whenever the source message/media becomes inaccessible or extraction fails.
8. Move to Elasticsearch, OpenSearch, Meilisearch, or Typesense when search needs richer ranking, highlighting, typo tolerance, media-derived text, or database portability.

This path gives the app a practical first search feature without locking the domain model to PostgreSQL forever. The key is to keep normalization, search request handling, and centralized authorization in backend code behind a search service abstraction so the storage/index implementation can change later.

## Future Higher-Scale Path

- Add a durable `SearchOutbox` table for message-created, message-edited, message-deleted, and media-text-extracted events.
- Add a separate search indexer worker once the dedicated index exists.
- Consume `MEDIA_TEXT_EXTRACTED` events from `media-processing-service` after OCR/transcript rows are committed.
- Introduce RabbitMQ job queues if we need background processing before Kafka.
- Introduce Kafka only when multiple consumers need replayable message events.
- Move search reads to a dedicated search engine.
- Store search documents with:
  - message ID
  - group ID
  - sender ID
  - timestamp
  - raw text fields
  - normalized text fields
  - source type
  - media timestamps for video/audio matches
- Add OCR for images.
- Add speech-to-text for audio and videos.
- Add video OCR using sampled frames or scene-change detection.
- Include source type and media timestamps in search result snippets so users can jump to the right attachment or time range.
- Add result highlighting.
- Add typo tolerance.
- Add advanced filters.
- Add index rebuild tooling.
- Add index-lag dashboards and alerts.

## Open Questions

- Should system messages be searchable by their rendered human-readable text, or excluded from MVP search?
- What exact server-enforced minimum and maximum lengths should `q` and `limit` use?
- Should global search sort only by newest first with `(timestamp DESC, messageId DESC)`, or combine recency with relevance later?
- Should the UI group global results by group, or show one chronological result stream?
- How long can search be eventually consistent after sending or editing a message?
- Which OCR and speech-to-text provider is acceptable from a privacy and cost perspective?
- Do we need Vietnamese-specific ranking beyond accent-insensitive matching?
- How long should tombstoned `MediaExtractedText` rows be retained before hard cleanup?

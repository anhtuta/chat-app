# Object Storage Replacement for Chat Media

## Current Problem

As of **2026-10-01**, the `minio/minio` and `minio/mc` repositories are no longer
available from Docker Hub. More importantly, the open-source MinIO repository is
archived and explicitly says that it is no longer maintained. Historical images
may still be available from Quay, but they are frozen and will not receive
security fixes.

This affects the current local stack directly because
`chat-app-backend/docker-compose.yml` uses `minio/minio:latest`. A cached image
can hide the problem, while a fresh developer machine or CI runner fails to pull
it.

Changing only the image name is not enough for a durable solution:

- `chat-app-backend` uses the MinIO Java SDK for bucket creation, presigned
  `PUT`/`GET`, `HEAD`, delete, and the complete multipart lifecycle.
- `media-processing` uses the MinIO Java SDK to download originals and upload
  video derivatives.
- Browser uploads need CORS and SigV4 presigned URLs.
- Videos need reliable range reads for seeking and playback.
- Large videos, currently allowed up to 200 MB, need multipart
  create/upload/complete/abort behavior.
- The replacement must preserve object keys and database references during
  migration.

This document evaluates replacements. It does **not** change the running storage
service yet.

## Recommendation

Use this plan:

1. **Local development and CI: use RustFS 1.0 behind the standard S3 API.**
   It is the closest operational replacement for the current one-container
   MinIO setup, is Apache-2.0 licensed, supports the required presigned and
   multipart operations, and documents compatibility with MinIO clients.
2. **Production: use a managed S3-compatible provider.**
   The final production choice is still open between **AWS S3** and
   **Cloudflare R2**. AWS S3 has the lowest API-compatibility risk; R2 is
   attractive if media egress cost is materially better after validation.
3. **Self-hosted production is now a fallback path, not the primary plan.**
   If managed production storage is rejected later, evaluate SeaweedFS first
   and Garage second. SeaweedFS has the longer project history and broad S3
   coverage; Garage is attractive for a small geo-distributed cluster but uses
   roughly 3x replication and has a smaller S3 feature surface.

Do **not** make RustFS the production default solely because it is the easiest
Docker Compose swap. RustFS reached 1.0 GA only in September 2026, and its
official materials currently give mixed maturity signals: the GA announcement
calls the core production-ready, while its repository still labels distributed
mode as under testing. It is a strong pilot candidate, not yet an automatic
durability decision.

### What “managed object storage” means

A managed object store is operated by a cloud provider. The application still
uses the S3 API, but the provider owns the storage servers, disk replacement,
replication, upgrades, and most availability work. We pay for stored data,
requests, and sometimes downloaded data instead of running the storage cluster.
It is not installed in this project's Docker Compose stack.

Suggested production order:

1. **AWS S3** when minimizing compatibility and durability risk matters most,
   especially if the application already runs on AWS.
2. **Cloudflare R2** when users frequently stream chat videos and internet
   egress cost is expected to dominate.

Local development should still use a local S3-compatible container such as
RustFS. Local and production storage do not need to be the same product if both
pass the same provider-neutral S3 contract tests.

### Can local and production use different storage?

Yes. This is the preferred path for the current project:

- **Local development and CI:** RustFS
- **Production:** AWS S3 or Cloudflare R2

This split is safe **if the application code talks only to a provider-neutral
S3 contract**. The application should not rely on MinIO-specific or
RustFS-specific APIs outside the storage adapter layer.

Practical implication:

- We can start with RustFS locally **before** choosing production storage.
- We should still finish the standard S3 implementation early, so production is
  a configuration and validation choice rather than another storage rewrite.

### How much code change would a later production choice require?

There are two different answers:

1. **If we keep the current MinIO-specific implementation:** choosing AWS S3 or
   R2 later will require a **meaningful but bounded refactor** in both
   `chat-app-backend` and `media-processing`.
2. **If we first finish a provider-neutral S3 adapter:** choosing between AWS
   S3 and R2 later should be **small**, mostly configuration, validation, and
   environment setup.

Current code reality:

- The backend already has a useful abstraction in `ObjectStorageProvider`, but
  the concrete `S3ObjectStorageProvider` is still mostly a placeholder and does
  not yet implement real presigning, multipart lifecycle, existence checks, or
  deletion.
- The active MinIO implementation uses the MinIO Java SDK directly for the
  multipart and presign flow.
- The media-processing worker is also still wired to MinIO-specific uploader and
  downloader implementations.

Recommended engineering path:

1. Keep the product decision open: **RustFS locally, AWS S3 or R2 in
   production**.
2. Replace MinIO SDK coupling with **AWS SDK v2 S3 client/presigner-based**
   adapters in both services.
3. Treat RustFS, AWS S3, and R2 as configuration targets behind the same
   contract.
4. Run the same contract suite against all three before cutover.

If we follow that path, deciding "AWS S3 vs R2" later should not require large
application changes.

### Recommendation for the current deployment

Production currently runs on VPS/dedicated hosted servers and serves active
external users. The plan is to move production media to a managed
S3-compatible provider after comparing cost and privacy.

For this context:

- Compare **Cloudflare R2 first against AWS S3**. R2 may reduce the cost of
  repeatedly delivering chat videos; AWS S3 remains the compatibility and
  operational baseline.
- Do not replace production MinIO with a single-node RustFS container. That
  would restore image availability but would not improve the single-node
  failure domain.
- If the managed production path is rejected later, use a real multi-node
  self-hosted PoC:
  SeaweedFS first, Garage second, or RustFS only after its four-node distributed
  topology passes failure testing.
- Keep the storage region close to both the production VPS and the majority of
  users to control latency and network cost.

Current sizing/topology information further narrows the choice:

- Production MinIO currently stores approximately **100 GB–1 TB**.
- Three production servers can be dedicated to self-hosted object storage.
- Most users are in Vietnam/Southeast Asia and the VPS is elsewhere in Asia.
- Garage can use the three available nodes, but three-way replication requires
  roughly three times the usable media capacity.
- SeaweedFS remains the first self-hosted PoC at this size.
- RustFS is not the preferred production choice with only three available
  servers because its documented production multi-node guidance starts at four.
- For managed storage, benchmark an APAC placement close to the exact VPS
  region and users. Include worker traffic between the VPS and storage, not only
  browser delivery.

### Immediate build-recovery option

If fresh environments are blocked before the replacement is ready, temporarily
pin the last historical Quay image by immutable tag and preferably mirror it
into a registry controlled by the project. Do not use `latest`.

This is only a short bridge:

- the image is unmaintained and unpatched;
- continued availability is not guaranteed;
- it does not resolve the production support and security risk.

## Application-Specific Acceptance Criteria

A candidate is acceptable only if an automated test proves all of the
following against the exact pinned server release:

| Area                | Required behavior                                                                                                     |
| ------------------- | --------------------------------------------------------------------------------------------------------------------- |
| Authentication      | AWS Signature V4 with static service credentials                                                                      |
| Addressing          | Path-style access; region handling compatible with current config                                                     |
| Bucket              | Create-if-missing and bucket existence check                                                                          |
| Single-part upload  | Browser `PUT` through a presigned URL, including CORS                                                                 |
| Multipart upload    | Create, presign every part, upload, complete with ETags, abort, and clean incomplete uploads                          |
| Object verification | `HEAD` returns size, ETag, and content type                                                                           |
| Reads               | Presigned `GET`, full worker download, and HTTP range requests for video                                              |
| Writes              | Worker-side streaming `PUT` for poster and transcoded video objects                                                   |
| Cleanup             | Idempotent object delete and not-found behavior                                                                       |
| Consistency         | A successful complete/put is immediately visible to `HEAD` and `GET`                                                  |
| Failure safety      | Restart during upload, full disk, unavailable node, and interrupted multipart completion do not acknowledge lost data |
| Operations          | Health check, metrics, backup/restore, upgrade, disk replacement, and documented recovery                             |

Advanced S3 features such as object lock, bucket versioning, replication APIs,
and object tags are not currently used by the chat application. They should not
outweigh correctness of the smaller contract above.

## Candidate Summary

| Candidate                 | Current chat API fit                                    | Operational weight                               | Maturity concern                                                         | Best fit here                                  |
| ------------------------- | ------------------------------------------------------- | ------------------------------------------------ | ------------------------------------------------------------------------ | ---------------------------------------------- |
| **RustFS 1.0**            | High on documented core operations                      | Low for one node; higher for 4-node production   | Very young GA; distributed maturity must be proven                       | **Recommended local/CI pilot**                 |
| **SeaweedFS**             | High; broad S3 matrix                                   | Medium; more components and concepts             | Mature project, but production data-protection/support tiers need review | **First self-hosted production PoC**           |
| **Garage**                | High for this app's core subset                         | Low to medium; minimum 3-node production cluster | Narrower S3 surface; no erasure coding                                   | Self-hosted, small geo-distributed deployments |
| **Ceph RGW**              | High                                                    | Very high unless Ceph already exists             | Mature, but operationally complex                                        | Existing Ceph/platform team                    |
| **Managed S3-compatible** | Highest with AWS S3; high but vendor-specific elsewhere | Lowest infrastructure burden                     | Cost, egress, residency, and vendor dependency                           | **Recommended production path if allowed**     |
| **Zenko CloudServer**     | Potentially high                                        | Medium to high                                   | Better aligned to multi-cloud gateway use cases                          | Existing Scality/Zenko architecture            |
| **Apache Ozone**          | Core API is plausible                                   | Very high                                        | Hadoop-oriented architecture; presign details still evolving             | Large data platform, not this chat stack       |

The summary is deliberately qualitative. Storage durability should not be
selected from benchmark numbers or GitHub stars without failure and restore
testing on the intended topology.

## Possible Solutions

### 1. RustFS

RustFS is a Rust-based S3-compatible object store under Apache-2.0. Its
compatibility matrix lists presigned `GET`/`PUT`, common object operations, and
multipart create/upload/complete/abort as tested. Its documentation recommends
standard AWS SDKs, although it also claims MinIO SDK compatibility.

**Pros**

- Closest shape to the current MinIO developer experience.
- Single-node mode works well for a local Docker Compose pilot.
- Permissive Apache-2.0 license.
- Documented support for the exact core operations used by this application.
- Reed-Solomon erasure coding is available for distributed storage.
- MinIO-style environment-variable aliases can ease an initial experiment.
- Active project with published images and a recent 1.0 GA release.

**Cons**

- 1.0 GA is only weeks old as of this decision.
- Limited public long-duration evidence for node failure, healing, upgrades,
  network partitions, and large production clusters.
- Production multi-node/multi-disk guidance starts at four servers, which is
  much heavier than the current single-container setup.
- Official pages currently conflict on how ready distributed mode is.
- “Compatible with MinIO SDK” does not prove compatibility with every method
  currently called by `MinioAsyncClient`; our multipart tests remain mandatory.

**Recommendation for our problem:** **Yes for local development and CI, after a
contract-test spike. Conditional for production after a multi-node soak and
restore exercise.**

### 2. SeaweedFS

SeaweedFS is an Apache-2.0 distributed storage system started in 2014. It
exposes an S3 gateway and supports presigned URLs, object operations, range
reads, CORS, and the full multipart lifecycle.

**Pros**

- Much longer operating and project history than RustFS.
- Broad, explicit S3 operation matrix.
- Designed for large numbers of files and horizontally scalable blob storage.
- Supports replication for hot data and erasure coding for warm data.
- Can expose S3, filesystem, and other interfaces if future workloads need
  them.
- Permissive Apache-2.0 license for the open-source project.

**Cons**

- More moving parts than MinIO/RustFS: master, volume servers, filer, and S3
  gateway concepts must be understood and operated.
- A small installation can be easy to start but production topology, repair,
  backup, and metadata recovery are more involved.
- Some advanced recovery, self-healing, and customizable erasure-coding
  capabilities are advertised in the Enterprise edition; the exact open-source
  production guarantees and support terms must be confirmed.
- Its flexibility is unnecessary overhead if the only need is one S3 bucket.
- Presigned URL behavior has had path-style/content-type nuances, so browser
  upload tests are essential.

**Recommendation for our problem:** **Yes as the first self-hosted production
PoC, especially if a four-node RustFS deployment is undesirable. Not the
simplest local-development replacement.**

### 3. Garage

Garage is an AGPL-3.0, lightweight, masterless S3-compatible object store
designed for clusters spread across locations with modest hardware and network
links. It supports SigV4, presigned URLs, CORS, range reads, lifecycle rules,
and the multipart operations needed by this app.

**Pros**

- Low CPU and memory requirements.
- Straightforward three-node production recommendation.
- Designed for geo-distributed and heterogeneous hardware.
- Read-after-write consistency is available in the default consistent mode.
- Built-in block checksums, deduplication, and compression.
- Good match for a small team that intentionally wants multi-site self-hosting.

**Cons**

- Uses replication rather than erasure coding; the recommended factor of three
  costs roughly 3x raw storage, material for videos.
- Does not support bucket versioning, object lock, tagging, AWS-style policies,
  or S3 replication endpoints. These are not current blockers but reduce future
  flexibility.
- The S3 API endpoint needs a reverse proxy for TLS.
- Metadata snapshots and filesystem choices require care; Garage documents
  unclean-shutdown concerns for its default LMDB metadata engine.
- AGPL-3.0 requires legal review, especially if the service is modified and
  offered over a network.

**Recommendation for our problem:** **Conditional. Choose it when lightweight
geo-distribution matters more than storage efficiency and broad S3 parity.**

### 4. Ceph Object Gateway (RGW)

Ceph RGW exposes an S3-compatible API backed by a Ceph cluster. It supports the
core object operations, multipart uploads, CORS, presigned URLs, and range
reads.

**Pros**

- Mature, widely deployed distributed storage platform.
- Strong durability, erasure coding, replication, scrubbing, healing, and
  multi-site capabilities.
- Broad S3 support and established operational tooling.
- Scales far beyond the foreseeable media volume of a small chat application.
- Good choice when the organization already runs Ceph.

**Cons**

- Considerably more complex to deploy, monitor, upgrade, repair, and capacity
  plan than the application currently needs.
- A proper highly available deployment involves multiple storage and gateway
  daemons plus ingress/load balancing.
- High memory, disk, network, and operator-skill cost compared with the other
  candidates.
- Running Ceph only for this chat application's media is disproportionate.

**Recommendation for our problem:** **No for a standalone chat deployment. Yes
only if Ceph is already a supported platform service.**

### 5. Managed S3 and S3-Compatible Services

This category includes AWS S3 and compatible services such as Cloudflare R2,
Backblaze B2, and Wasabi.

**Pros**

- Removes disk repair, cluster quorum, upgrades, and storage-node monitoring
  from the application team.
- AWS S3 is the reference behavior for SigV4, presigned URLs, multipart, range
  reads, lifecycle rules, and SDKs.
- Built-in durability, encryption, lifecycle, metrics, and access controls.
- R2 can be attractive for frequently viewed chat video because of its egress
  model; B2 and Wasabi can have lower storage pricing than AWS.
- Easy future CDN integration.

**Cons**

- Ongoing storage, request, and network/egress charges.
- Requires network access and introduces an external dependency.
- Data residency, privacy, compliance, and account-level outage policy must be
  reviewed.
- “S3-compatible” providers have differences: for example, R2 and B2 do not
  support presigned browser `POST`, though this app uses presigned `PUT`.
- Does not replace the need for a convenient offline/local developer service.
- Migrating providers later can incur egress and operational cost.

**Recommendation for our problem:** **Yes for production if managed services
are allowed. Use AWS S3 for minimum compatibility risk; benchmark total cost
and validate R2/B2 if video delivery cost is dominant.**

### 6. Zenko CloudServer

Zenko CloudServer is an active Apache-2.0 S3-compatible server/gateway that can
front multiple storage backends. Its documented API includes CORS, object
operations, and multipart upload.

**Pros**

- Active project with a long release history.
- Broad S3 feature set and permissive license.
- Useful when one S3 endpoint must route to several clouds or backend systems.
- Backed by Scality's storage ecosystem.

**Cons**

- Multi-cloud gateway capability is not a current requirement.
- More architecture and configuration than a direct object store.
- Current authoritative documentation for this app's exact presigned
  multipart/browser flow is less clear than RustFS, SeaweedFS, or Garage.
- Adds another abstraction layer without solving a demonstrated need.

**Recommendation for our problem:** **No as the default. Reconsider only for a
future multi-cloud storage strategy.**

### 7. Apache Ozone

Apache Ozone is a distributed object store from the Hadoop ecosystem. A
separate stateless S3 Gateway maps S3 calls onto Ozone metadata and DataNodes.

**Pros**

- Apache project and Apache-2.0 license.
- Scalable architecture for very large data platforms.
- Supports core object operations and multipart upload through its S3 Gateway.
- Multiple stateless gateways can sit behind a load balancer.

**Cons**

- Requires the Ozone control plane and DataNodes in addition to the S3 Gateway.
- Operational scale and Hadoop-oriented architecture are excessive here.
- Presigned upload coverage has been evolving and needs release-specific
  validation.
- Larger security and deployment surface than every shortlisted option.

**Recommendation for our problem:** **No. It fits a large existing Ozone/Hadoop
platform, not a chat application's media service.**

## Candidates Not Shortlisted

- **OpenMaxIO / MinIO forks:** potentially preserve familiar behavior, but they
  currently have less independent production history than the shortlisted
  projects. Track them; do not move durable chat media to a fork solely to keep
  the old console.
- **VersityGW:** an S3 gateway over filesystem or other backends, not by itself
  the full distributed storage replacement being selected here.
- **JuiceFS:** a distributed filesystem that commonly uses object storage as a
  backend; it does not remove the need to choose durable object storage.
- **OpenStack Swift:** mature, but S3 access is an adapter and operating
  OpenStack infrastructure for this application alone is not justified.
- **LocalStack:** useful for API tests, not a production object store.
- **NFS/local disk served through an HTTP API:** simple initially, but recreates
  multipart, signing, range delivery, replication, repair, and failover in the
  application team. Do not build this.

## High-Level Architecture

Keep the application speaking a standard S3 contract, independent of the
selected server:

```mermaid
flowchart LR
    Browser[Chat browser] -->|Presigned PUT / UploadPart| S3[S3-compatible endpoint]
    Browser -->|Presigned GET / Range| S3
    Backend[chat-app-backend] -->|AWS SDK v2: sign, HEAD, multipart, delete| S3
    Worker[media-processing] -->|AWS SDK v2: GET original, PUT derivatives| S3
    Backend --> DB[(PostgreSQL object references)]
    Worker --> Backend
```

The target adapter should use the AWS SDK v2 `S3Client` and `S3Presigner` for
standard operations. The storage server should be selected by endpoint,
credentials, region, and path-style configuration rather than by importing its
vendor SDK.

Keeping a `MINIO` provider name while pointing it at RustFS or SeaweedFS would
work as a short spike but would preserve the wrong abstraction. A generic
`S3_COMPATIBLE` implementation makes later movement among RustFS, SeaweedFS,
Garage, Ceph, and managed providers substantially safer.

## Migration Plan

### Phase 0 - Restore reproducible builds

- Replace `minio/minio:latest` temporarily with an immutable, pullable bridge
  image or internal mirror.
- Record the image digest and verify a cold pull in CI.
- Do not expose the frozen service publicly.

Current Phase 0 bridge:

- Quay historical MinIO images are no longer usable here; registry requests now
  return `401 UNAUTHORIZED`, so the original Quay fallback is not reliable.
- `chat-app-backend/docker-compose.yml` now uses the pullable bridge image
  `cgr.dev/chainguard/minio@sha256:e7ca559d9f7c0b5f24f5f669bb92f40f3ca88d56273b808bf3a7c116c17d2ffa`.
- This digest was verified locally by:
  - pulling the image successfully,
  - confirming the `minio` CLI is present and reports
    `RELEASE.2026-09-22T19-25-18Z`,
  - starting the container with `server /data --console-address ":9001"`,
  - and checking `GET /minio/health/live`.
- This remains a short-lived local-development bridge only, until Phase 3 moves
  local development to RustFS.
- The remaining Phase 0 task is to repeat the same validation in CI or another
  clean bootstrap environment.

### Phase 1 - Add an executable storage contract suite

- Run the acceptance criteria in this document against a disposable container.
- Include an actual browser-CORS test, not only SDK calls.
- Upload a file above the multipart threshold and verify ETag handling.
- Verify video `Range` requests return `206 Partial Content`.
- Restart the storage process between multipart parts and after completion.
- Verify object count, total bytes, content hash, and metadata.

Current Phase 1 coverage:

- Added `chat-app-backend/src/test/java/com/hello/chatapp/storage/ObjectStorageContractIntegrationTest`.
- The suite starts a disposable MinIO-compatible container through the local
  Docker CLI instead of relying on a long-running developer stack.
- Verified:
  - `GET /minio/health/live`
  - single-part presigned `PUT` upload
  - object metadata visibility through storage `stat`
  - presigned `GET` readback of uploaded bytes
  - presigned `GET` with HTTP `Range` support returning `206 Partial Content`
  - object delete and missing-object behavior
  - multipart create/upload-part/complete with returned ETags
  - multipart abort leaving no finalized object behind
- Verified locally with:
  - `./mvnw -Dtest=ObjectStorageContractIntegrationTest test`

Still remaining in Phase 1:

- browser-origin CORS validation
- restart/interruption scenarios during multipart flows
- object count and content-hash inventory assertions

### Phase 2 - Remove MinIO SDK coupling

- Implement standard S3 operations with AWS SDK v2 in both Java services.
- Complete the currently placeholder `S3ObjectStorageProvider`.
- Introduce provider-neutral `S3_COMPATIBLE` configuration.
- Keep object keys, bucket name, database schema, and external API unchanged.
- Run existing media tests plus the new contract suite against current MinIO
  and the candidate to detect semantic differences.

### Phase 3 - Pilot RustFS for local development and CI

- Pin an exact RustFS 1.0.x image digest; do not use `latest`.
- Add explicit CORS, health checks, non-default credentials, and a persistent
  volume.
- Exercise single-part images and multipart videos end to end.
- Keep the temporary MinIO Compose profile available for one release as a
  rollback comparison.

### Phase 4 - Select the production path

- Run the contract suite against **AWS S3** and **Cloudflare R2**.
- Compare monthly storage, request, and egress estimates for both.
- Choose production between S3 and R2 after the contract suite and cost
  comparison.
- Keep the self-hosted evaluation as fallback only if managed production
  storage is rejected later.

### Phase 5 - Migrate existing objects

- Take an inventory by bucket, object count, bytes, and object-key prefix.
- Copy S3-to-S3 with a resumable tool such as `rclone`; preserve content type
  and other required metadata.
- Validate every object by key and size, with checksums where available.
- Use a short write freeze for final delta sync and endpoint cutover. Avoid
  long-lived application dual-write unless zero downtime is a hard requirement;
  dual-write creates partial-failure and reconciliation problems.
- Keep the old store read-only through the rollback window.
- Test rollback before deleting the source data.

## Operational Concerns

- Object storage redundancy is not a backup. Maintain a separately recoverable
  copy and test restore.
- Pin server images by version and digest and monitor security advisories.
- Configure lifecycle cleanup for abandoned multipart uploads.
- Alert on disk capacity, failed healing/repair, request error rate, latency,
  and incomplete multipart growth.
- Keep presigned URL TTLs short and credentials out of browser-visible config.
- Put TLS in front of every non-local S3 endpoint.
- Estimate video egress separately from storage; playback can dominate cost.
- Test upgrades with old objects and in-progress multipart uploads.

## Confirmed Decisions

- The replacement covers **both local development and production**; MinIO is
  currently used in both environments.
- The chosen direction is **RustFS for local development/CI** and a
  **managed S3-compatible provider for production**.
- The final production provider is still open between **AWS S3** and
  **Cloudflare R2**.
- Existing MinIO objects are durable application data and must be migrated.
- AGPL-3.0 infrastructure is acceptable, so Garage remains eligible.
- A one-time outage of up to 24 hours is tolerable if necessary.
- Production runs on VPS/dedicated hosted servers and has active external
  users.
- Production media currently occupies approximately 100 GB–1 TB.
- Three servers are available for a self-hosted production storage cluster.
- The production VPS is in Asia and most users are in Vietnam/Southeast Asia;
  the exact VPS region still needs to be recorded.
- During final cutover, existing media may remain readable while new uploads
  are paused for up to 30 minutes.
- Capacity and traffic must be measured for the current state and estimated for
  the next 12 months.
- Backup, data residency, encryption/KMS, and disaster recovery are not the
  first selection criteria, but production must still meet the minimum baseline
  below.

## Recommended Availability and Recovery Targets

A 24-hour window is acceptable as an **emergency ceiling** or for a pre-launch
system. It is too long as the normal target for production maintenance because
uploads, images, audio, files, and video playback would be unavailable.

Recommended initial targets for this chat application:

| Measure                                 | Recommended initial target                                                                               |
| --------------------------------------- | -------------------------------------------------------------------------------------------------------- |
| Planned storage maintenance             | No user-visible outage where possible; otherwise no more than 30 minutes per event                       |
| One-time migration cutover              | Bulk copy while MinIO remains online, then no more than 30 minutes of upload write-freeze for final sync |
| Hard migration ceiling                  | 2 hours normally; use the approved 24 hours only if online copy/cutover proves unsafe                    |
| Emergency recovery time objective (RTO) | Restore media service within 4 hours                                                                     |
| Recovery point objective (RPO)          | Lose no more than 24 hours of newly written media                                                        |
| Rollback window                         | Keep old MinIO data read-only for at least 7 days after cutover                                          |

Because the service has active external users, use online bulk copy plus a
short final upload write-freeze. The approved 24-hour window is a contingency,
not the planned outage.

Minimum production baseline even when advanced DR is deferred:

- TLS in transit and provider/storage-level encryption at rest;
- no customer-managed KMS requirement in the first migration;
- one separately recoverable copy of media data;
- at least 7 days of backup/rollback retention, without violating the
  application's configured 60-day media deletion policy;
- a documented restore procedure and one successful restore test before
  deleting the old MinIO copy;
- deploy storage in the same legal region as the application until a specific
  residency requirement is defined.

## Remaining Questions / Measurements

- TODO: Measure current MinIO bucket bytes, object count, largest object, daily
  uploads, monthly downloads/egress, and peak requests.
- TODO: Estimate those measurements at 12 months from expected users and
  growth.
- TODO: Record the exact production VPS provider/region, then compare latency
  and cross-provider transfer charges to APAC R2 and AWS S3 endpoints.
- TODO: For the three self-hosting servers, record disks per server, usable
  capacity, network capacity, and whether they are in independent failure
  domains.
- TODO: Choose the production provider between AWS S3 and Cloudflare R2 after
  cost estimation and contract tests.
- TODO: Keep the self-hosted fallback documented in case the managed production
  path is rejected later.

Until those items are answered, proceed with **RustFS locally** and keep the
final production provider open between **AWS S3** and **Cloudflare R2**.

## Sources

Accessed 2026-10-01:

- [MinIO repository and maintenance status](https://github.com/minio/minio)
- [Docker Hub image removal and temporary Quay fallback](https://www.stablebuild.com/blog/minio-images-disappeared-from-docker-hub)
- [RustFS S3 compatibility matrix](https://docs.rustfs.com/en/reference/s3-compatibility)
- [RustFS multi-node production guidance](https://docs.rustfs.com/en/installation/linux/multiple-node-multiple-disk)
- [RustFS 1.0 GA announcement](https://rustfs.com/blog/announcing-rustfs-1-0-0-ga/)
- [RustFS repository feature status](https://github.com/RustFS/RustFS)
- [SeaweedFS S3 API matrix](https://github.com/seaweedfs/seaweedfs/wiki/Amazon-S3-API)
- [SeaweedFS repository](https://github.com/seaweedfs/seaweedfs)
- [SeaweedFS Enterprise feature boundary](https://seaweedfs.com/)
- [Garage introduction and license](https://garagehq.deuxfleurs.fr/intro.html)
- [Garage S3 compatibility](https://deuxfleurs-org-garage-38.mintlify.app/api/s3-compatibility)
- [Garage production deployment guidance](https://garagehq.deuxfleurs.fr/documentation/cookbook/real-world/)
- [Ceph RGW S3 compliance](https://docs.ceph.com/en/squid/dev/radosgw/s3_compliance/)
- [Ceph RGW deployment](https://docs.ceph.com/en/squid/cephadm/services/rgw/)
- [Apache Ozone S3 interface](https://ozone.apache.org/docs/2.0.0/interface/s3.html)
- [Zenko CloudServer repository](https://github.com/scality/cloudserver)
- [AWS S3 presigned URLs](https://docs.aws.amazon.com/AmazonS3/latest/userguide/using-presigned-url.html)
- [Cloudflare R2 presigned URLs](https://developers.cloudflare.com/r2/api/s3/presigned-urls/)
- [Backblaze B2 S3-compatible API](https://www.backblaze.com/docs/cloud-storage-s3-compatible-api)

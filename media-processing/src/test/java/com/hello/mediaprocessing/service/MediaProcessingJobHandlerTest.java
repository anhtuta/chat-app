package com.hello.mediaprocessing.service;

import com.hello.mediaprocessing.config.MediaProcessingStorageProperties;
import com.hello.mediaprocessing.config.MediaProcessingWorkerProperties;
import com.hello.mediaprocessing.constant.MediaProcessingFailureReason;
import com.hello.mediaprocessing.constant.MediaProcessingJobStatus;
import com.hello.mediaprocessing.constant.MediaProcessingMessageType;
import com.hello.mediaprocessing.constant.ObjectStorageProviderType;
import com.hello.mediaprocessing.constant.ProcessingTarget;
import com.hello.mediaprocessing.constant.VideoTranscodeMode;
import com.hello.mediaprocessing.exception.MediaProcessingSourceLoadException;
import com.hello.mediaprocessing.exception.VideoTranscodeException;
import com.hello.mediaprocessing.model.MediaProcessingJobMessage;
import com.hello.mediaprocessing.model.MediaProcessingResult;
import com.hello.mediaprocessing.model.ObjectStorageUploadResult;
import com.hello.mediaprocessing.model.VideoMetadata;
import com.hello.mediaprocessing.model.VideoTranscodeResult;
import com.hello.mediaprocessing.storage.ObjectStorageUploader;
import com.hello.mediaprocessing.storage.ObjectStorageUploaderRegistry;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.nio.file.Path;
import java.util.List;
import org.hibernate.validator.messageinterpolation.ParameterMessageInterpolator;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the initial worker state machine added in the implemented phases.
 */
class MediaProcessingJobHandlerTest {

    private final Validator validator = Validation.byDefaultProvider()
            .configure()
            .messageInterpolator(new ParameterMessageInterpolator())
            .buildValidatorFactory()
            .getValidator();

    /**
     * Verifies that a valid video job with an enabled target reaches the dispatched state.
     */
    @Test
    void handle_validVideoMetadataJob_dispatches() {
        MediaProcessingJobHandler handler = new MediaProcessingJobHandler(
                new MediaProcessingWorkerProperties(),
                new InMemoryMediaProcessingJobDeduplicationStore(),
                new SuccessfulSourceLoader(),
                new SuccessfulVideoMetadataExtractor(),
                new ReuseOriginalTranscoder(),
                minioUploaderRegistry(),
                new NoopResultSink(),
                validator);

        MediaProcessingJobStatus status = handler.handle(buildVideoJob("job-1", List.of(ProcessingTarget.METADATA)));

        assertThat(status).isEqualTo(MediaProcessingJobStatus.MEDIA_READY);
    }

    /**
     * Verifies that the local idempotency layer skips duplicate deliveries after the first dispatch.
     */
    @Test
    void handle_duplicateJob_skipsSecondDelivery() {
        MediaProcessingJobHandler handler = new MediaProcessingJobHandler(
                new MediaProcessingWorkerProperties(),
                new InMemoryMediaProcessingJobDeduplicationStore(),
                new SuccessfulSourceLoader(),
                new SuccessfulVideoMetadataExtractor(),
                new ReuseOriginalTranscoder(),
                minioUploaderRegistry(),
                new NoopResultSink(),
                validator);

        MediaProcessingJobMessage job = buildVideoJob("job-dup", List.of(ProcessingTarget.METADATA));
        assertThat(handler.handle(job)).isEqualTo(MediaProcessingJobStatus.MEDIA_READY);
        assertThat(handler.handle(job)).isEqualTo(MediaProcessingJobStatus.SKIPPED_DUPLICATE);
    }

    /**
     * Verifies that jobs defer cleanly when all requested targets are currently disabled.
     */
    @Test
    void handle_targetDisabled_defersJob() {
        MediaProcessingWorkerProperties properties = new MediaProcessingWorkerProperties();
        properties.getFeatureFlags().setVideoTranscode(false);
        MediaProcessingJobHandler handler = new MediaProcessingJobHandler(
                properties,
                new InMemoryMediaProcessingJobDeduplicationStore(),
                new SuccessfulSourceLoader(),
                new SuccessfulVideoMetadataExtractor(),
                new ReuseOriginalTranscoder(),
                minioUploaderRegistry(),
                new NoopResultSink(),
                validator);

        MediaProcessingJobStatus status = handler.handle(buildVideoJob("job-2", List.of(ProcessingTarget.TRANSCODE)));

        assertThat(status).isEqualTo(MediaProcessingJobStatus.DEFERRED_NO_ENABLED_TARGETS);
    }

    /**
     * Verifies that metadata extraction can complete while later-phase targets remain pending.
     */
    @Test
    void handle_metadataPlusThumbnail_staysInProgress() {
        MediaProcessingWorkerProperties properties = new MediaProcessingWorkerProperties();
        properties.getFeatureFlags().setVideoPoster(true);
        MediaProcessingJobHandler handler = new MediaProcessingJobHandler(
                properties,
                new InMemoryMediaProcessingJobDeduplicationStore(),
                new SuccessfulSourceLoader(),
                new SuccessfulVideoMetadataExtractor(),
                new ReuseOriginalTranscoder(),
                minioUploaderRegistry(),
                new NoopResultSink(),
                validator);

        MediaProcessingJobStatus status = handler.handle(
                buildVideoJob("job-partial", List.of(ProcessingTarget.METADATA, ProcessingTarget.THUMBNAIL)));

        assertThat(status).isEqualTo(MediaProcessingJobStatus.PROCESSING_IN_PROGRESS);
    }

    /**
     * Verifies that enabled but unimplemented targets stay pending instead of returning DISPATCHED.
     */
    @Test
    void handle_thumbnailOnly_staysPendingWithoutDispatchReturn() {
        MediaProcessingWorkerProperties properties = new MediaProcessingWorkerProperties();
        properties.getFeatureFlags().setVideoMetadata(false);
        properties.getFeatureFlags().setVideoPoster(true);
        CapturingResultSink resultSink = new CapturingResultSink();
        MediaProcessingJobHandler handler = new MediaProcessingJobHandler(
                properties,
                new InMemoryMediaProcessingJobDeduplicationStore(),
                new SuccessfulSourceLoader(),
                new SuccessfulVideoMetadataExtractor(),
                new ReuseOriginalTranscoder(),
                minioUploaderRegistry(),
                resultSink,
                validator);

        MediaProcessingJobStatus status = handler.handle(
                buildVideoJob("job-thumbnail-only", List.of(ProcessingTarget.THUMBNAIL)));

        assertThat(status).isEqualTo(MediaProcessingJobStatus.PROCESSING_IN_PROGRESS);
        assertThat(resultSink.lastResult()).isNotNull();
        assertThat(resultSink.lastResult().status()).isEqualTo(MediaProcessingJobStatus.PROCESSING_IN_PROGRESS);
        assertThat(resultSink.lastResult().completedTargets()).isEmpty();
        assertThat(resultSink.lastResult().pendingTargets()).containsExactly(ProcessingTarget.THUMBNAIL);
        assertThat(resultSink.lastResult().videoMetadata()).isNull();
    }

    /**
     * Verifies that deferred jobs are not permanently deduplicated and can be retried later.
     */
    @Test
    void handle_deferredJob_allowsRetry() {
        MediaProcessingWorkerProperties properties = new MediaProcessingWorkerProperties();
        properties.getFeatureFlags().setVideoTranscode(false);
        InMemoryMediaProcessingJobDeduplicationStore deduplicationStore = new InMemoryMediaProcessingJobDeduplicationStore();
        MediaProcessingJobHandler handler = new MediaProcessingJobHandler(
                properties,
                deduplicationStore,
                new SuccessfulSourceLoader(),
                new SuccessfulVideoMetadataExtractor(),
                new ReuseOriginalTranscoder(),
                minioUploaderRegistry(),
                new NoopResultSink(),
                validator);

        MediaProcessingJobMessage job = buildVideoJob("job-deferred", List.of(ProcessingTarget.TRANSCODE));

        assertThat(handler.handle(job)).isEqualTo(MediaProcessingJobStatus.DEFERRED_NO_ENABLED_TARGETS);
        assertThat(handler.handle(job)).isEqualTo(MediaProcessingJobStatus.DEFERRED_NO_ENABLED_TARGETS);
    }

    /**
     * Verifies that source-load failures release the in-progress claim so the job can be retried.
     */
    @Test
    void handle_sourceLoadFailure_allowsRetry() {
        InMemoryMediaProcessingJobDeduplicationStore deduplicationStore = new InMemoryMediaProcessingJobDeduplicationStore();
        MediaProcessingJobHandler failingHandler = new MediaProcessingJobHandler(
                new MediaProcessingWorkerProperties(),
                deduplicationStore,
                new FailingSourceLoader(MediaProcessingFailureReason.SOURCE_MISSING),
                new SuccessfulVideoMetadataExtractor(),
                new ReuseOriginalTranscoder(),
                minioUploaderRegistry(),
                new NoopResultSink(),
                validator);
        MediaProcessingJobHandler successfulHandler = new MediaProcessingJobHandler(
                new MediaProcessingWorkerProperties(),
                deduplicationStore,
                new SuccessfulSourceLoader(),
                new SuccessfulVideoMetadataExtractor(),
                new ReuseOriginalTranscoder(),
                minioUploaderRegistry(),
                new NoopResultSink(),
                validator);

        MediaProcessingJobMessage job = buildVideoJob("job-retry-failure", List.of(ProcessingTarget.METADATA));

        assertThat(failingHandler.handle(job)).isEqualTo(MediaProcessingJobStatus.PROCESSING_FAILED);
        assertThat(successfulHandler.handle(job)).isEqualTo(MediaProcessingJobStatus.MEDIA_READY);
    }

    /**
     * Verifies that partial progress releases the in-progress claim so later phases can resume.
     */
    @Test
    void handle_partialProgress_allowsRetryUntilMediaReady() {
        MediaProcessingWorkerProperties properties = new MediaProcessingWorkerProperties();
        properties.getFeatureFlags().setVideoPoster(true);
        InMemoryMediaProcessingJobDeduplicationStore deduplicationStore = new InMemoryMediaProcessingJobDeduplicationStore();
        MediaProcessingJobHandler handler = new MediaProcessingJobHandler(
                properties,
                deduplicationStore,
                new SuccessfulSourceLoader(),
                new SuccessfulVideoMetadataExtractor(),
                new ReuseOriginalTranscoder(),
                minioUploaderRegistry(),
                new NoopResultSink(),
                validator);

        MediaProcessingJobMessage job =
                buildVideoJob("job-partial-retry", List.of(ProcessingTarget.METADATA, ProcessingTarget.THUMBNAIL));

        assertThat(handler.handle(job)).isEqualTo(MediaProcessingJobStatus.PROCESSING_IN_PROGRESS);
        assertThat(handler.handle(job)).isEqualTo(MediaProcessingJobStatus.PROCESSING_IN_PROGRESS);
    }

    /**
     * Verifies that source-loading failures are translated into a processing-failed status.
     */
    @Test
    void handle_sourceMissing_marksProcessingFailed() {
        MediaProcessingJobHandler handler = new MediaProcessingJobHandler(
                new MediaProcessingWorkerProperties(),
                new InMemoryMediaProcessingJobDeduplicationStore(),
                new FailingSourceLoader(MediaProcessingFailureReason.SOURCE_MISSING),
                new SuccessfulVideoMetadataExtractor(),
                new ReuseOriginalTranscoder(),
                minioUploaderRegistry(),
                new NoopResultSink(),
                validator);

        MediaProcessingJobStatus status = handler.handle(buildVideoJob("job-missing", List.of(ProcessingTarget.METADATA)));

        assertThat(status).isEqualTo(MediaProcessingJobStatus.PROCESSING_FAILED);
    }

    /**
     * Verifies that an already chat-ready MP4 is reused as the playback object without uploading a duplicate.
     */
    @Test
    void handle_transcodeFastPath_reusesOriginalObject() {
        MediaProcessingWorkerProperties properties = new MediaProcessingWorkerProperties();
        properties.getFeatureFlags().setVideoTranscode(true);
        CapturingResultSink resultSink = new CapturingResultSink();
        RecordingUploader uploader = new RecordingUploader();
        MediaProcessingJobHandler handler = new MediaProcessingJobHandler(
                properties,
                new InMemoryMediaProcessingJobDeduplicationStore(),
                new SuccessfulSourceLoader(),
                new SuccessfulVideoMetadataExtractor(),
                new ReuseOriginalTranscoder(),
                minioUploaderRegistry(uploader),
                resultSink,
                validator);

        MediaProcessingJobStatus status = handler.handle(
                buildVideoJob("job-reuse", List.of(ProcessingTarget.METADATA, ProcessingTarget.TRANSCODE)));

        assertThat(status).isEqualTo(MediaProcessingJobStatus.MEDIA_READY);
        assertThat(resultSink.lastResult().transcodedObjectKey()).isEqualTo("media/7/video/demo.mp4");
        assertThat(resultSink.lastResult().reusedOriginalObject()).isTrue();
        assertThat(resultSink.lastResult().completedTargets())
                .containsExactlyInAnyOrder(ProcessingTarget.METADATA, ProcessingTarget.TRANSCODE);
        assertThat(uploader.lastObjectKey()).isNull();
    }

    /**
     * Verifies that a converted playback file is uploaded under a derived object key.
     */
    @Test
    void handle_transcodeReencode_uploadsDerivedObject() {
        MediaProcessingWorkerProperties properties = new MediaProcessingWorkerProperties();
        properties.getFeatureFlags().setVideoTranscode(true);
        CapturingResultSink resultSink = new CapturingResultSink();
        RecordingUploader uploader = new RecordingUploader();
        MediaProcessingJobHandler handler = new MediaProcessingJobHandler(
                properties,
                new InMemoryMediaProcessingJobDeduplicationStore(),
                new SuccessfulSourceLoader(),
                new SuccessfulVideoMetadataExtractor(),
                new ReencodeTranscoder(),
                minioUploaderRegistry(uploader),
                resultSink,
                validator);

        MediaProcessingJobStatus status = handler.handle(
                buildVideoJob("job-reencode", List.of(ProcessingTarget.TRANSCODE)));

        assertThat(status).isEqualTo(MediaProcessingJobStatus.MEDIA_READY);
        assertThat(resultSink.lastResult().transcodedObjectKey()).isEqualTo("media/7/video/demo.transcoded.mp4");
        assertThat(resultSink.lastResult().reusedOriginalObject()).isFalse();
        assertThat(uploader.lastObjectKey()).isEqualTo("media/7/video/demo.transcoded.mp4");
        assertThat(uploader.lastContentType()).isEqualTo("video/mp4");
    }

    /**
     * Verifies that transcode failures mark the job failed and release the in-progress claim.
     */
    @Test
    void handle_transcodeFailure_marksProcessingFailedAndAllowsRetry() {
        MediaProcessingWorkerProperties properties = new MediaProcessingWorkerProperties();
        properties.getFeatureFlags().setVideoTranscode(true);
        InMemoryMediaProcessingJobDeduplicationStore deduplicationStore = new InMemoryMediaProcessingJobDeduplicationStore();
        MediaProcessingJobHandler failingHandler = new MediaProcessingJobHandler(
                properties,
                deduplicationStore,
                new SuccessfulSourceLoader(),
                new SuccessfulVideoMetadataExtractor(),
                new FailingTranscoder(),
                minioUploaderRegistry(),
                new NoopResultSink(),
                validator);
        MediaProcessingJobHandler successfulHandler = new MediaProcessingJobHandler(
                properties,
                deduplicationStore,
                new SuccessfulSourceLoader(),
                new SuccessfulVideoMetadataExtractor(),
                new ReuseOriginalTranscoder(),
                minioUploaderRegistry(),
                new NoopResultSink(),
                validator);

        MediaProcessingJobMessage job = buildVideoJob("job-transcode-fail", List.of(ProcessingTarget.TRANSCODE));

        assertThat(failingHandler.handle(job)).isEqualTo(MediaProcessingJobStatus.PROCESSING_FAILED);
        assertThat(successfulHandler.handle(job)).isEqualTo(MediaProcessingJobStatus.MEDIA_READY);
    }

    /**
     * Builds a representative video-processing job for worker-handler tests.
     *
     * @param jobId idempotency key to embed in the payload
     * @param targets requested outputs for the worker to evaluate
     * @return processing job payload for the test case
     */
    private MediaProcessingJobMessage buildVideoJob(String jobId, List<ProcessingTarget> targets) {
        return new MediaProcessingJobMessage(
                jobId,
                100L,
                200L,
                MediaProcessingMessageType.VIDEO,
                ObjectStorageProviderType.MINIO,
                "chat-media",
                "media/7/video/demo.mp4",
                "video/mp4",
                targets);
    }

    /**
     * Builds a MinIO uploader registry that records uploads when a recording uploader is supplied.
     *
     * @return registry containing a no-op MinIO uploader
     */
    private ObjectStorageUploaderRegistry minioUploaderRegistry() {
        return minioUploaderRegistry(new RecordingUploader());
    }

    /**
     * Builds a MinIO uploader registry around the supplied uploader.
     *
     * @param uploader uploader to register
     * @return registry configured for MinIO
     */
    private ObjectStorageUploaderRegistry minioUploaderRegistry(ObjectStorageUploader uploader) {
        MediaProcessingStorageProperties storageProperties = new MediaProcessingStorageProperties();
        storageProperties.setProvider(ObjectStorageProviderType.MINIO);
        return new ObjectStorageUploaderRegistry(List.of(uploader), storageProperties);
    }

    /**
     * Test double that returns a synthetic local source file without touching object storage.
     */
    private static final class SuccessfulSourceLoader implements MediaProcessingSourceLoader {

        /**
         * Returns a synthetic local source handle for handler tests.
         *
         * @param job job payload being handled
         * @return fake loaded source bound to a test-only workspace manager
         */
        @Override
        public LoadedMediaSource load(MediaProcessingJobMessage job) {
            return new LoadedMediaSource(
                    Path.of("/tmp/" + job.jobId()),
                    Path.of("/tmp/" + job.jobId() + "/demo.mp4"),
                    1024L,
                    job.requestedMimeType(),
                    new NoopWorkspaceManager(),
                    false);
        }
    }

    /**
     * Test double that returns a fixed metadata payload without invoking ffprobe.
     */
    private static final class SuccessfulVideoMetadataExtractor implements VideoMetadataExtractor {

        /**
         * Returns stable metadata for handler tests.
         *
         * @param localFile ignored by this test double
         * @param fallbackMimeType MIME type that should flow into the metadata result
         * @return synthetic metadata payload
         */
        @Override
        public VideoMetadata extract(Path localFile, String fallbackMimeType) {
            return new VideoMetadata(
                    12_345L,
                    1920,
                    1080,
                    fallbackMimeType,
                    "mov,mp4,m4a,3gp,3g2,mj2",
                    "h264",
                    "aac");
        }
    }

    /**
     * Test double that reports the source file as already chat-ready.
     */
    private static final class ReuseOriginalTranscoder implements VideoTranscoder {

        /**
         * Returns the source path as the playback file without conversion.
         *
         * @param sourceFile downloaded original
         * @param outputFile ignored
         * @param sourceMetadata ignored
         * @return reuse result pointing at the source file
         */
        @Override
        public VideoTranscodeResult transcode(Path sourceFile, Path outputFile, VideoMetadata sourceMetadata) {
            return new VideoTranscodeResult(VideoTranscodeMode.REUSE_ORIGINAL, sourceFile);
        }
    }

    /**
     * Test double that reports a derived playback file that still needs uploading.
     */
    private static final class ReencodeTranscoder implements VideoTranscoder {

        /**
         * Returns the requested output path as a re-encoded playback file.
         *
         * @param sourceFile ignored
         * @param outputFile workspace playback path
         * @param sourceMetadata ignored
         * @return re-encode result pointing at the output file
         */
        @Override
        public VideoTranscodeResult transcode(Path sourceFile, Path outputFile, VideoMetadata sourceMetadata) {
            return new VideoTranscodeResult(VideoTranscodeMode.REENCODE, outputFile);
        }
    }

    /**
     * Test double that always fails conversion.
     */
    private static final class FailingTranscoder implements VideoTranscoder {

        /**
         * Throws a deterministic transcode failure.
         *
         * @param sourceFile ignored
         * @param outputFile ignored
         * @param sourceMetadata ignored
         * @return never returns because the method always throws
         */
        @Override
        public VideoTranscodeResult transcode(Path sourceFile, Path outputFile, VideoMetadata sourceMetadata) {
            throw new VideoTranscodeException(
                    MediaProcessingFailureReason.TRANSCODE_FAILED, "Simulated transcode failure");
        }
    }

    /**
     * Records the last upload request without writing to object storage.
     */
    private static final class RecordingUploader implements ObjectStorageUploader {

        private String lastObjectKey;
        private String lastContentType;

        /**
         * Returns MinIO so the handler registry can resolve the job provider.
         *
         * @return {@link ObjectStorageProviderType#MINIO}
         */
        @Override
        public ObjectStorageProviderType getType() {
            return ObjectStorageProviderType.MINIO;
        }

        /**
         * Stores upload arguments for later assertions.
         *
         * @param bucket destination bucket
         * @param objectKey destination object key
         * @param sourcePath local file that would be uploaded
         * @param contentType stored content type
         * @return synthetic upload metadata
         */
        @Override
        public ObjectStorageUploadResult upload(String bucket, String objectKey, Path sourcePath, String contentType) {
            this.lastObjectKey = objectKey;
            this.lastContentType = contentType;
            return new ObjectStorageUploadResult(objectKey, 1L, contentType);
        }

        private String lastObjectKey() {
            return lastObjectKey;
        }

        private String lastContentType() {
            return lastContentType;
        }
    }

    /**
     * Test double that always raises a typed source-load failure.
     */
    private static final class FailingSourceLoader implements MediaProcessingSourceLoader {

        private final MediaProcessingFailureReason failureReason;

        private FailingSourceLoader(MediaProcessingFailureReason failureReason) {
            this.failureReason = failureReason;
        }

        /**
         * Throws a deterministic source-load failure for handler tests.
         *
         * @param job ignored because the failure is preconfigured
         * @return never returns because the method always throws
         */
        @Override
        public LoadedMediaSource load(MediaProcessingJobMessage job) {
            throw new MediaProcessingSourceLoadException(failureReason, "Simulated source load failure");
        }
    }

    /**
     * Test workspace manager variant that suppresses file cleanup because no real files are created.
     */
    private static final class NoopWorkspaceManager extends MediaProcessingWorkspaceManager {

        private NoopWorkspaceManager() {
            super(new com.hello.mediaprocessing.config.MediaProcessingWorkspaceProperties());
        }

        /**
         * Suppresses cleanup for synthetic test paths.
         *
         * @param workspaceDirectory ignored in this test double
         */
        @Override
        public void cleanupWorkspaceQuietly(Path workspaceDirectory) {
            // No-op for unit tests.
        }
    }

    /**
     * Test sink that records the most recent worker result for assertions.
     */
    private static final class CapturingResultSink implements MediaProcessingResultSink {

        private MediaProcessingResult lastResult;

        /**
         * Stores the latest worker result emitted by the handler under test.
         *
         * @param result normalized worker output
         */
        @Override
        public void accept(MediaProcessingResult result) {
            this.lastResult = result;
        }

        /**
         * Returns the most recently captured worker result.
         *
         * @return last result accepted by this sink, or {@code null} when none was recorded
         */
        private MediaProcessingResult lastResult() {
            return lastResult;
        }
    }

    /**
     * Test sink that accepts worker results without persisting or publishing them.
     */
    private static final class NoopResultSink implements MediaProcessingResultSink {

        /**
         * Ignores worker results because handler tests only assert returned status values.
         *
         * @param result normalized worker output
         */
        @Override
        public void accept(MediaProcessingResult result) {
            // No-op for unit tests.
        }
    }
}

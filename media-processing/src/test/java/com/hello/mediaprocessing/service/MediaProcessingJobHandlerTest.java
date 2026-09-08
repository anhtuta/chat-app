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
import com.hello.mediaprocessing.exception.VideoPosterGenerationException;
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
import java.util.Objects;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.validator.HibernateValidator;
import org.hibernate.validator.messageinterpolation.ParameterMessageInterpolator;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the initial worker state machine added in the implemented phases.
 */
class MediaProcessingJobHandlerTest {

    private final Validator validator = createValidator();

    /**
     * Builds the bean validator used by the worker handler.
     *
     * @return non-null validator instance for the test class
     */
    private Validator createValidator() {
        return Objects.requireNonNull(Validation.byProvider(HibernateValidator.class)
                .configure()
                .messageInterpolator(new ParameterMessageInterpolator())
                .buildValidatorFactory()
                .getValidator());
    }

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
                new SuccessfulPosterGenerator(),
                new ReuseOriginalTranscoder(),
                new NoopMobileRenditionGenerator(),
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
                new SuccessfulPosterGenerator(),
                new ReuseOriginalTranscoder(),
                new NoopMobileRenditionGenerator(),
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
                new SuccessfulPosterGenerator(),
                new ReuseOriginalTranscoder(),
                new NoopMobileRenditionGenerator(),
                minioUploaderRegistry(),
                new NoopResultSink(),
                validator);

        MediaProcessingJobStatus status = handler.handle(buildVideoJob("job-2", List.of(ProcessingTarget.TRANSCODE)));

        assertThat(status).isEqualTo(MediaProcessingJobStatus.DEFERRED_NO_ENABLED_TARGETS);
    }

    /**
     * Verifies that implemented work can complete while a later-phase preview target remains pending.
     */
    @Test
    void handle_metadataPlusPreview_staysInProgress() {
        MediaProcessingWorkerProperties properties = new MediaProcessingWorkerProperties();
        properties.getFeatureFlags().setVideoPoster(true);
        properties.getFeatureFlags().setVideoTranscode(false);
        MediaProcessingJobHandler handler = new MediaProcessingJobHandler(
                properties,
                new InMemoryMediaProcessingJobDeduplicationStore(),
                new SuccessfulSourceLoader(),
                new SuccessfulVideoMetadataExtractor(),
                new SuccessfulPosterGenerator(),
                new ReuseOriginalTranscoder(),
                new NoopMobileRenditionGenerator(),
                minioUploaderRegistry(),
                new NoopResultSink(),
                validator);

        MediaProcessingJobStatus status = handler.handle(
                buildVideoJob("job-partial", List.of(ProcessingTarget.METADATA, ProcessingTarget.PREVIEW)));

        assertThat(status).isEqualTo(MediaProcessingJobStatus.PROCESSING_IN_PROGRESS);
    }

    /**
     * Verifies that enabled but unimplemented targets stay pending instead of returning DISPATCHED.
     */
    @Test
    void handle_thumbnailOnly_generatesPosterAndMarksReady() {
        MediaProcessingWorkerProperties properties = new MediaProcessingWorkerProperties();
        properties.getFeatureFlags().setVideoMetadata(false);
        properties.getFeatureFlags().setVideoPoster(true);
        CapturingResultSink resultSink = new CapturingResultSink();
        RecordingUploader uploader = new RecordingUploader();
        MediaProcessingJobHandler handler = new MediaProcessingJobHandler(
                properties,
                new InMemoryMediaProcessingJobDeduplicationStore(),
                new SuccessfulSourceLoader(),
                new SuccessfulVideoMetadataExtractor(),
                new SuccessfulPosterGenerator(),
                new ReuseOriginalTranscoder(),
                new NoopMobileRenditionGenerator(),
                minioUploaderRegistry(uploader),
                resultSink,
                validator);

        MediaProcessingJobStatus status = handler.handle(
                buildVideoJob("job-thumbnail-only", List.of(ProcessingTarget.THUMBNAIL)));

        assertThat(status).isEqualTo(MediaProcessingJobStatus.MEDIA_READY);
        assertThat(resultSink.lastResult()).isNotNull();
        assertThat(resultSink.lastResult().status()).isEqualTo(MediaProcessingJobStatus.MEDIA_READY);
        assertThat(resultSink.lastResult().completedTargets()).containsExactly(ProcessingTarget.THUMBNAIL);
        assertThat(resultSink.lastResult().pendingTargets()).isEmpty();
        assertThat(resultSink.lastResult().thumbnailObjectKey()).isEqualTo("media/7/video/demo.thumbnail.jpg");
        assertThat(uploader.lastObjectKey()).isEqualTo("media/7/video/demo.thumbnail.jpg");
        assertThat(uploader.lastContentType()).isEqualTo("image/jpeg");
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
                new SuccessfulPosterGenerator(),
                new ReuseOriginalTranscoder(),
                new NoopMobileRenditionGenerator(),
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
                new SuccessfulPosterGenerator(),
                new ReuseOriginalTranscoder(),
                new NoopMobileRenditionGenerator(),
                minioUploaderRegistry(),
                new NoopResultSink(),
                validator);
        MediaProcessingJobHandler successfulHandler = new MediaProcessingJobHandler(
                new MediaProcessingWorkerProperties(),
                deduplicationStore,
                new SuccessfulSourceLoader(),
                new SuccessfulVideoMetadataExtractor(),
                new SuccessfulPosterGenerator(),
                new ReuseOriginalTranscoder(),
                new NoopMobileRenditionGenerator(),
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
        properties.getFeatureFlags().setVideoTranscode(false);
        InMemoryMediaProcessingJobDeduplicationStore deduplicationStore = new InMemoryMediaProcessingJobDeduplicationStore();
        MediaProcessingJobHandler handler = new MediaProcessingJobHandler(
                properties,
                deduplicationStore,
                new SuccessfulSourceLoader(),
                new SuccessfulVideoMetadataExtractor(),
                new SuccessfulPosterGenerator(),
                new ReuseOriginalTranscoder(),
                new NoopMobileRenditionGenerator(),
                minioUploaderRegistry(),
                new NoopResultSink(),
                validator);

        MediaProcessingJobMessage job =
                buildVideoJob("job-partial-retry", List.of(ProcessingTarget.METADATA, ProcessingTarget.PREVIEW));

        assertThat(handler.handle(job)).isEqualTo(MediaProcessingJobStatus.PROCESSING_IN_PROGRESS);
        assertThat(handler.handle(job)).isEqualTo(MediaProcessingJobStatus.PROCESSING_IN_PROGRESS);
    }

    /**
     * Verifies that source-loading failures are translated into a processing-failed status.
     */
    @Test
    void handle_sourceMissing_marksProcessingFailed() {
        CapturingResultSink resultSink = new CapturingResultSink();
        MediaProcessingJobHandler handler = new MediaProcessingJobHandler(
                new MediaProcessingWorkerProperties(),
                new InMemoryMediaProcessingJobDeduplicationStore(),
                new FailingSourceLoader(MediaProcessingFailureReason.SOURCE_MISSING),
                new SuccessfulVideoMetadataExtractor(),
                new SuccessfulPosterGenerator(),
                new ReuseOriginalTranscoder(),
                new NoopMobileRenditionGenerator(),
                minioUploaderRegistry(),
                resultSink,
                validator);

        MediaProcessingJobStatus status = handler.handle(buildVideoJob("job-missing", List.of(ProcessingTarget.METADATA)));

        assertThat(status).isEqualTo(MediaProcessingJobStatus.PROCESSING_FAILED);
        assertThat(resultSink.lastResult()).isNotNull();
        assertThat(resultSink.lastResult().status()).isEqualTo(MediaProcessingJobStatus.PROCESSING_FAILED);
        assertThat(resultSink.lastResult().originalObjectKey()).isEqualTo("media/7/video/demo.mp4");
    }

    /**
     * Verifies that poster generation uploads a derived JPEG and reports its object key.
     */
    @Test
    void handle_thumbnail_uploadsDerivedPosterObject() {
        MediaProcessingWorkerProperties properties = new MediaProcessingWorkerProperties();
        properties.getFeatureFlags().setVideoMetadata(false);
        properties.getFeatureFlags().setVideoPoster(true);
        CapturingResultSink resultSink = new CapturingResultSink();
        RecordingUploader uploader = new RecordingUploader();
        MediaProcessingJobHandler handler = new MediaProcessingJobHandler(
                properties,
                new InMemoryMediaProcessingJobDeduplicationStore(),
                new SuccessfulSourceLoader(),
                new SuccessfulVideoMetadataExtractor(),
                new SuccessfulPosterGenerator(),
                new ReuseOriginalTranscoder(),
                new NoopMobileRenditionGenerator(),
                minioUploaderRegistry(uploader),
                resultSink,
                validator);

        MediaProcessingJobStatus status = handler.handle(
                buildVideoJob("job-poster", List.of(ProcessingTarget.THUMBNAIL)));

        assertThat(status).isEqualTo(MediaProcessingJobStatus.MEDIA_READY);
        assertThat(resultSink.lastResult().thumbnailObjectKey()).isEqualTo("media/7/video/demo.thumbnail.jpg");
        assertThat(uploader.lastObjectKey()).isEqualTo("media/7/video/demo.thumbnail.jpg");
        assertThat(uploader.lastContentType()).isEqualTo("image/jpeg");
    }

    /**
     * Verifies that poster-generation failures mark the job failed and release the in-progress claim.
     */
    @Test
    void handle_thumbnailFailure_marksProcessingFailedAndAllowsRetry() {
        MediaProcessingWorkerProperties properties = new MediaProcessingWorkerProperties();
        properties.getFeatureFlags().setVideoMetadata(false);
        properties.getFeatureFlags().setVideoPoster(true);
        InMemoryMediaProcessingJobDeduplicationStore deduplicationStore = new InMemoryMediaProcessingJobDeduplicationStore();
        MediaProcessingJobHandler failingHandler = new MediaProcessingJobHandler(
                properties,
                deduplicationStore,
                new SuccessfulSourceLoader(),
                new SuccessfulVideoMetadataExtractor(),
                new FailingPosterGenerator(),
                new ReuseOriginalTranscoder(),
                new NoopMobileRenditionGenerator(),
                minioUploaderRegistry(),
                new NoopResultSink(),
                validator);
        MediaProcessingJobHandler successfulHandler = new MediaProcessingJobHandler(
                properties,
                deduplicationStore,
                new SuccessfulSourceLoader(),
                new SuccessfulVideoMetadataExtractor(),
                new SuccessfulPosterGenerator(),
                new ReuseOriginalTranscoder(),
                new NoopMobileRenditionGenerator(),
                minioUploaderRegistry(),
                new NoopResultSink(),
                validator);

        MediaProcessingJobMessage job = buildVideoJob("job-poster-fail", List.of(ProcessingTarget.THUMBNAIL));

        assertThat(failingHandler.handle(job)).isEqualTo(MediaProcessingJobStatus.PROCESSING_FAILED);
        assertThat(successfulHandler.handle(job)).isEqualTo(MediaProcessingJobStatus.MEDIA_READY);
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
                new SuccessfulPosterGenerator(),
                new ReuseOriginalTranscoder(),
                new NoopMobileRenditionGenerator(),
                minioUploaderRegistry(uploader),
                resultSink,
                validator);

        MediaProcessingJobStatus status = handler.handle(
                buildVideoJob("job-reuse", List.of(ProcessingTarget.METADATA, ProcessingTarget.TRANSCODE)));

        assertThat(status).isEqualTo(MediaProcessingJobStatus.MEDIA_READY);
        assertThat(resultSink.lastResult().thumbnailObjectKey()).isNull();
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
                new SuccessfulPosterGenerator(),
                new ReencodeTranscoder(),
                new NoopMobileRenditionGenerator(),
                minioUploaderRegistry(uploader),
                resultSink,
                validator);

        MediaProcessingJobStatus status = handler.handle(
                buildVideoJob("job-reencode", List.of(ProcessingTarget.TRANSCODE)));

        assertThat(status).isEqualTo(MediaProcessingJobStatus.MEDIA_READY);
        assertThat(resultSink.lastResult().thumbnailObjectKey()).isNull();
        assertThat(resultSink.lastResult().transcodedObjectKey()).isEqualTo("media/7/video/demo.transcoded.mp4");
        assertThat(resultSink.lastResult().reusedOriginalObject()).isFalse();
        assertThat(uploader.lastObjectKey()).isEqualTo("media/7/video/demo.transcoded.mp4");
        assertThat(uploader.lastContentType()).isEqualTo("video/mp4");
    }

    /**
     * Verifies that an enabled mobile-rendition path uploads a secondary 480p MP4 without changing the callback contract yet.
     */
    @Test
    void handle_mobileRenditionEnabled_uploadsSecondaryMp4() {
        MediaProcessingWorkerProperties properties = new MediaProcessingWorkerProperties();
        properties.getFeatureFlags().setVideoTranscode(true);
        properties.getFeatureFlags().setVideoMobileRenditions(true);
        CapturingResultSink resultSink = new CapturingResultSink();
        RecordingUploader uploader = new RecordingUploader();
        MediaProcessingJobHandler handler = new MediaProcessingJobHandler(
                properties,
                new InMemoryMediaProcessingJobDeduplicationStore(),
                new SuccessfulSourceLoader(),
                new SuccessfulVideoMetadataExtractor(),
                new SuccessfulPosterGenerator(),
                new ReencodeTranscoder(),
                new SuccessfulMobileRenditionGenerator(),
                minioUploaderRegistry(uploader),
                resultSink,
                validator);

        MediaProcessingJobStatus status = handler.handle(
                buildVideoJob("job-mobile-rendition", List.of(ProcessingTarget.TRANSCODE)));

        assertThat(status).isEqualTo(MediaProcessingJobStatus.MEDIA_READY);
        assertThat(resultSink.lastResult().transcodedObjectKey()).isEqualTo("media/7/video/demo.transcoded.mp4");
        assertThat(uploader.uploadedObjectKeys())
                .containsExactly("media/7/video/demo.transcoded.mp4", "media/7/video/demo.480p.mp4");
        assertThat(uploader.uploadedContentTypes()).containsExactly("video/mp4", "video/mp4");
    }

    /**
     * Verifies that a mobile-rendition failure does not downgrade the canonical playback result.
     */
    @Test
    void handle_mobileRenditionFailure_keepsCanonicalReady() {
        MediaProcessingWorkerProperties properties = new MediaProcessingWorkerProperties();
        properties.getFeatureFlags().setVideoTranscode(true);
        properties.getFeatureFlags().setVideoMobileRenditions(true);
        CapturingResultSink resultSink = new CapturingResultSink();
        RecordingUploader uploader = new RecordingUploader();
        MediaProcessingJobHandler handler = new MediaProcessingJobHandler(
                properties,
                new InMemoryMediaProcessingJobDeduplicationStore(),
                new SuccessfulSourceLoader(),
                new SuccessfulVideoMetadataExtractor(),
                new SuccessfulPosterGenerator(),
                new ReencodeTranscoder(),
                new FailingMobileRenditionGenerator(),
                minioUploaderRegistry(uploader),
                resultSink,
                validator);

        MediaProcessingJobStatus status = handler.handle(
                buildVideoJob("job-mobile-rendition-fail", List.of(ProcessingTarget.TRANSCODE)));

        assertThat(status).isEqualTo(MediaProcessingJobStatus.MEDIA_READY);
        assertThat(resultSink.lastResult().transcodedObjectKey()).isEqualTo("media/7/video/demo.transcoded.mp4");
        assertThat(uploader.uploadedObjectKeys()).containsExactly("media/7/video/demo.transcoded.mp4");
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
                new SuccessfulPosterGenerator(),
                new FailingTranscoder(),
                new NoopMobileRenditionGenerator(),
                minioUploaderRegistry(),
                new NoopResultSink(),
                validator);
        MediaProcessingJobHandler successfulHandler = new MediaProcessingJobHandler(
                properties,
                deduplicationStore,
                new SuccessfulSourceLoader(),
                new SuccessfulVideoMetadataExtractor(),
                new SuccessfulPosterGenerator(),
                new ReuseOriginalTranscoder(),
                new NoopMobileRenditionGenerator(),
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
     * Test double that writes a placeholder JPEG so poster generation behaves like a successful ffmpeg run.
     */
    private static final class SuccessfulPosterGenerator implements VideoPosterGenerator {

        /**
         * Writes a tiny placeholder file to the requested poster path.
         *
         * @param sourceFile ignored
         * @param outputFile poster output path
         * @param sourceMetadata ignored
         * @return the same output path after writing placeholder bytes
         */
        @Override
        public Path generate(Path sourceFile, Path outputFile, VideoMetadata sourceMetadata) {
            try {
                Path parent = outputFile.getParent();
                if (parent != null) {
                    java.nio.file.Files.createDirectories(parent);
                }
                java.nio.file.Files.writeString(outputFile, "jpeg");
                return outputFile;
            } catch (java.io.IOException e) {
                throw new RuntimeException(e);
            }
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
     * Test double that always fails poster generation.
     */
    private static final class FailingPosterGenerator implements VideoPosterGenerator {

        /**
         * Throws a deterministic poster-generation failure.
         *
         * @param sourceFile ignored
         * @param outputFile ignored
         * @param sourceMetadata ignored
         * @return never returns because the method always throws
         */
        @Override
        public Path generate(Path sourceFile, Path outputFile, VideoMetadata sourceMetadata) {
            throw new VideoPosterGenerationException(
                    MediaProcessingFailureReason.POSTER_GENERATION_FAILED,
                    "Simulated poster generation failure");
        }
    }

    /**
     * Test double that suppresses the optional mobile-rendition step.
     */
    private static final class NoopMobileRenditionGenerator implements VideoMobileRenditionGenerator {

        /**
         * Returns no file so the handler behaves as if the mobile rendition was intentionally skipped.
         *
         * @param canonicalInputFile ignored
         * @param outputFile ignored
         * @param sourceMetadata ignored
         * @param canonicalObjectSize ignored
         * @return empty because no rendition is produced
         */
        @Override
        public java.util.Optional<Path> generate(
                Path canonicalInputFile,
                Path outputFile,
                VideoMetadata sourceMetadata,
                long canonicalObjectSize) {
            return java.util.Optional.empty();
        }
    }

    /**
     * Test double that writes a placeholder MP4 for the optional mobile-rendition path.
     */
    private static final class SuccessfulMobileRenditionGenerator implements VideoMobileRenditionGenerator {

        /**
         * Writes a small placeholder file to the requested rendition path.
         *
         * @param canonicalInputFile ignored
         * @param outputFile rendition output path
         * @param sourceMetadata ignored
         * @param canonicalObjectSize ignored
         * @return generated placeholder path
         */
        @Override
        public java.util.Optional<Path> generate(
                Path canonicalInputFile,
                Path outputFile,
                VideoMetadata sourceMetadata,
                long canonicalObjectSize) {
            try {
                Path parent = outputFile.getParent();
                if (parent != null) {
                    java.nio.file.Files.createDirectories(parent);
                }
                java.nio.file.Files.writeString(outputFile, "mobile-mp4");
                return java.util.Optional.of(outputFile);
            } catch (java.io.IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * Test double that always fails the optional mobile-rendition step.
     */
    private static final class FailingMobileRenditionGenerator implements VideoMobileRenditionGenerator {

        /**
         * Throws a deterministic mobile-rendition failure.
         *
         * @param canonicalInputFile ignored
         * @param outputFile ignored
         * @param sourceMetadata ignored
         * @param canonicalObjectSize ignored
         * @return never returns because the method always throws
         */
        @Override
        public java.util.Optional<Path> generate(
                Path canonicalInputFile,
                Path outputFile,
                VideoMetadata sourceMetadata,
                long canonicalObjectSize) {
            throw new com.hello.mediaprocessing.exception.VideoRenditionGenerationException(
                    MediaProcessingFailureReason.RENDITION_GENERATION_FAILED,
                    "Simulated mobile rendition failure");
        }
    }

    /**
     * Records the last upload request without writing to object storage.
     */
    private static final class RecordingUploader implements ObjectStorageUploader {

        private String lastObjectKey;
        private String lastContentType;
        private final List<String> uploadedObjectKeys = new ArrayList<>();
        private final List<String> uploadedContentTypes = new ArrayList<>();

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
            this.uploadedObjectKeys.add(objectKey);
            this.uploadedContentTypes.add(contentType);
            long objectSize = objectKey.endsWith(".transcoded.mp4") ? 16L * 1024 * 1024 : 4L * 1024 * 1024;
            return new ObjectStorageUploadResult(objectKey, objectSize, contentType);
        }

        private String lastObjectKey() {
            return lastObjectKey;
        }

        private String lastContentType() {
            return lastContentType;
        }

        private List<String> uploadedObjectKeys() {
            return uploadedObjectKeys;
        }

        private List<String> uploadedContentTypes() {
            return uploadedContentTypes;
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

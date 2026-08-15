package com.hello.mediaprocessing.service;

import com.hello.mediaprocessing.config.MediaProcessingWorkerProperties;
import com.hello.mediaprocessing.constant.MediaProcessingFailureReason;
import com.hello.mediaprocessing.constant.MediaProcessingJobStatus;
import com.hello.mediaprocessing.constant.MediaProcessingMessageType;
import com.hello.mediaprocessing.constant.ObjectStorageProviderType;
import com.hello.mediaprocessing.constant.ProcessingTarget;
import com.hello.mediaprocessing.model.MediaProcessingJobMessage;
import com.hello.mediaprocessing.model.MediaProcessingResult;
import com.hello.mediaprocessing.model.VideoMetadata;
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
                new NoopResultSink(),
                validator);

        MediaProcessingJobStatus status = handler.handle(
                buildVideoJob("job-partial", List.of(ProcessingTarget.METADATA, ProcessingTarget.THUMBNAIL)));

        assertThat(status).isEqualTo(MediaProcessingJobStatus.PROCESSING_IN_PROGRESS);
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
                new NoopResultSink(),
                validator);

        MediaProcessingJobStatus status = handler.handle(buildVideoJob("job-missing", List.of(ProcessingTarget.METADATA)));

        assertThat(status).isEqualTo(MediaProcessingJobStatus.PROCESSING_FAILED);
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

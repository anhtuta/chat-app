package com.hello.mediaprocessing.service;

import com.hello.mediaprocessing.config.MediaProcessingWorkerProperties;
import com.hello.mediaprocessing.job.MediaProcessingJobMessage;
import com.hello.mediaprocessing.job.MediaProcessingJobStatus;
import com.hello.mediaprocessing.job.MediaProcessingMessageType;
import com.hello.mediaprocessing.job.ProcessingTarget;
import org.hibernate.validator.messageinterpolation.ParameterMessageInterpolator;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
                validator);

        MediaProcessingJobStatus status = handler.handle(buildVideoJob("job-1", List.of(ProcessingTarget.METADATA)));

        assertEquals(MediaProcessingJobStatus.DISPATCHED, status);
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
                validator);

        MediaProcessingJobMessage job = buildVideoJob("job-dup", List.of(ProcessingTarget.METADATA));
        assertEquals(MediaProcessingJobStatus.DISPATCHED, handler.handle(job));
        assertEquals(MediaProcessingJobStatus.SKIPPED_DUPLICATE, handler.handle(job));
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
                validator);

        MediaProcessingJobStatus status = handler.handle(buildVideoJob("job-2", List.of(ProcessingTarget.TRANSCODE)));

        assertEquals(MediaProcessingJobStatus.DEFERRED_NO_ENABLED_TARGETS, status);
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
                validator);

        MediaProcessingJobStatus status = handler.handle(buildVideoJob("job-missing", List.of(ProcessingTarget.METADATA)));

        assertEquals(MediaProcessingJobStatus.PROCESSING_FAILED, status);
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
                "MINIO",
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
}

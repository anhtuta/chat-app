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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MediaProcessingJobHandlerTest {

    private final Validator validator = Validation.byDefaultProvider()
            .configure()
            .messageInterpolator(new ParameterMessageInterpolator())
            .buildValidatorFactory()
            .getValidator();

    @Test
    void handle_validVideoMetadataJob_dispatches() {
        MediaProcessingJobHandler handler = new MediaProcessingJobHandler(
                new MediaProcessingWorkerProperties(),
                new InMemoryMediaProcessingJobDeduplicationStore(),
                validator);

        MediaProcessingJobStatus status = handler.handle(buildVideoJob("job-1", List.of(ProcessingTarget.METADATA)));

        assertEquals(MediaProcessingJobStatus.DISPATCHED, status);
    }

    @Test
    void handle_duplicateJob_skipsSecondDelivery() {
        MediaProcessingJobHandler handler = new MediaProcessingJobHandler(
                new MediaProcessingWorkerProperties(),
                new InMemoryMediaProcessingJobDeduplicationStore(),
                validator);

        MediaProcessingJobMessage job = buildVideoJob("job-dup", List.of(ProcessingTarget.METADATA));
        assertEquals(MediaProcessingJobStatus.DISPATCHED, handler.handle(job));
        assertEquals(MediaProcessingJobStatus.SKIPPED_DUPLICATE, handler.handle(job));
    }

    @Test
    void handle_targetDisabled_defersJob() {
        MediaProcessingWorkerProperties properties = new MediaProcessingWorkerProperties();
        properties.getFeatureFlags().setVideoTranscode(false);
        MediaProcessingJobHandler handler = new MediaProcessingJobHandler(
                properties,
                new InMemoryMediaProcessingJobDeduplicationStore(),
                validator);

        MediaProcessingJobStatus status = handler.handle(buildVideoJob("job-2", List.of(ProcessingTarget.TRANSCODE)));

        assertEquals(MediaProcessingJobStatus.DEFERRED_NO_ENABLED_TARGETS, status);
    }

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
}

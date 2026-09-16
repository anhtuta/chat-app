package com.hello.mediaprocessing.controller;

import com.hello.mediaprocessing.constant.MediaProcessingJobStatus;
import com.hello.mediaprocessing.dto.LocalMediaProcessingTriggerRequest;
import com.hello.mediaprocessing.dto.LocalMediaProcessingTriggerResponse;
import com.hello.mediaprocessing.model.MediaProcessingJobMessage;
import com.hello.mediaprocessing.service.LastMediaProcessingResultHolder;
import com.hello.mediaprocessing.service.LocalMediaProcessingJobFactory;
import com.hello.mediaprocessing.service.MediaProcessingJobHandler;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Post;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import jakarta.validation.Valid;

/**
 * Local-only HTTP entrypoint that runs the same worker handler without RabbitMQ or chat-app-backend.
 */
@Controller("/local/media-processing")
@Requires(property = "media-processing.local-trigger.enabled", value = "true")
public class LocalMediaProcessingTriggerController {

    private final LocalMediaProcessingJobFactory jobFactory;
    private final MediaProcessingJobHandler jobHandler;
    private final LastMediaProcessingResultHolder lastResultHolder;

    public LocalMediaProcessingTriggerController(
            LocalMediaProcessingJobFactory jobFactory,
            MediaProcessingJobHandler jobHandler,
            LastMediaProcessingResultHolder lastResultHolder) {
        this.jobFactory = jobFactory;
        this.jobHandler = jobHandler;
        this.lastResultHolder = lastResultHolder;
    }

    /**
     * Downloads the named object from storage and runs metadata extraction plus transcode by default.
     *
     * @param request object key and optional job fields
     * @return handler status and, when emitted, the normalized worker result
     */
    @Post("/jobs")
    @ExecuteOn(TaskExecutors.BLOCKING)
    public LocalMediaProcessingTriggerResponse trigger(@Body @Valid LocalMediaProcessingTriggerRequest request) {
        MediaProcessingJobMessage job = jobFactory.toJob(request);
        MediaProcessingJobStatus status = jobHandler.handle(job);
        return new LocalMediaProcessingTriggerResponse(status, lastResultHolder.getForJob(job.jobId()));
    }
}

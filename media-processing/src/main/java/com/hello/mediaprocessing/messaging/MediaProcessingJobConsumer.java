package com.hello.mediaprocessing.messaging;

import com.hello.mediaprocessing.job.MediaProcessingJobMessage;
import com.hello.mediaprocessing.service.MediaProcessingJobHandler;
import io.micronaut.context.annotation.Requires;
import io.micronaut.rabbitmq.annotation.Queue;
import io.micronaut.rabbitmq.annotation.RabbitListener;
import jakarta.validation.Valid;

/**
 * RabbitMQ listener that hands queued processing jobs to the worker handler.
 */
@RabbitListener
@Requires(property = "media-processing.worker.enabled", value = "true")
@Requires(property = "media-processing.worker.handoff", value = "RABBITMQ")
public class MediaProcessingJobConsumer {

    private final MediaProcessingJobHandler jobHandler;

    public MediaProcessingJobConsumer(MediaProcessingJobHandler jobHandler) {
        this.jobHandler = jobHandler;
    }

    /**
     * Receives a single validated job message from the configured RabbitMQ queue.
     *
     * @param job queue payload describing the source media and requested outputs
     */
    @Queue("${media-processing.worker.queue}")
    public void receive(@Valid MediaProcessingJobMessage job) {
        jobHandler.handle(job);
    }
}

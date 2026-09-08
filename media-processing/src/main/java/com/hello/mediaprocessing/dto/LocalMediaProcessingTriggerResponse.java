package com.hello.mediaprocessing.dto;

import com.hello.mediaprocessing.constant.MediaProcessingJobStatus;
import com.hello.mediaprocessing.model.MediaProcessingResult;
import io.micronaut.serde.annotation.Serdeable;

/**
 * Local-only response after a manually triggered processing job.
 *
 * @param status terminal or intermediate worker status returned by the handler
 * @param result last sink payload for this attempt, or {@code null} when the handler failed before emitting a result
 */
@Serdeable
public record LocalMediaProcessingTriggerResponse(
        MediaProcessingJobStatus status, MediaProcessingResult result) {
}

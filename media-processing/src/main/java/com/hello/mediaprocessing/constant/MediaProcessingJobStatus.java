package com.hello.mediaprocessing.constant;

import io.micronaut.serde.annotation.Serdeable;

/**
 * Describes the worker-side lifecycle state recorded while handling a processing job.
 */
@Serdeable
public enum MediaProcessingJobStatus {
    RECEIVED,
    VALIDATED,
    DISPATCHED,
    PROCESSING_IN_PROGRESS,
    MEDIA_READY,
    SKIPPED_DUPLICATE,
    DEFERRED_NO_ENABLED_TARGETS,
    REJECTED_INVALID,
    PROCESSING_FAILED
}

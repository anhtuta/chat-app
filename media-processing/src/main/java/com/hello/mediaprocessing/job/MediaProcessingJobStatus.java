package com.hello.mediaprocessing.job;

/**
 * Describes the worker-side lifecycle state recorded while handling a processing job.
 */
public enum MediaProcessingJobStatus {
    RECEIVED,
    VALIDATED,
    DISPATCHED,
    SKIPPED_DUPLICATE,
    DEFERRED_NO_ENABLED_TARGETS,
    REJECTED_INVALID,
    PROCESSING_FAILED
}

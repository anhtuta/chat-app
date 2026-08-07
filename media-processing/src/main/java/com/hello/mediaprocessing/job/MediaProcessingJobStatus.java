package com.hello.mediaprocessing.job;

public enum MediaProcessingJobStatus {
    RECEIVED,
    VALIDATED,
    DISPATCHED,
    SKIPPED_DUPLICATE,
    DEFERRED_NO_ENABLED_TARGETS,
    REJECTED_INVALID,
    PROCESSING_FAILED
}

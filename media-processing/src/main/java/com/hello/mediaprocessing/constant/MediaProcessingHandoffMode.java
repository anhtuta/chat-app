package com.hello.mediaprocessing.constant;

/**
 * Enumerates the upstream delivery mechanisms that can feed work into the service.
 */
public enum MediaProcessingHandoffMode {
    RABBITMQ,
    JOB_TABLE,
    OUTBOX
}

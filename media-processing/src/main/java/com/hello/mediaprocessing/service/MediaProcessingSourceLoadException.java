package com.hello.mediaprocessing.service;

import com.hello.mediaprocessing.constant.MediaProcessingFailureReason;

/**
 * Signals that the worker could not materialize a usable local source file for a job.
 */
public class MediaProcessingSourceLoadException extends RuntimeException {

    private final MediaProcessingFailureReason failureReason;

    public MediaProcessingSourceLoadException(MediaProcessingFailureReason failureReason, String message, Throwable cause) {
        super(message, cause);
        this.failureReason = failureReason;
    }

    public MediaProcessingSourceLoadException(MediaProcessingFailureReason failureReason, String message) {
        super(message);
        this.failureReason = failureReason;
    }

    public MediaProcessingFailureReason getFailureReason() {
        return failureReason;
    }
}

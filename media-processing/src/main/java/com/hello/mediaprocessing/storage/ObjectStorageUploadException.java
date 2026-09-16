package com.hello.mediaprocessing.storage;

import com.hello.mediaprocessing.constant.MediaProcessingFailureReason;

/**
 * Signals that a derived media object could not be written to object storage.
 */
public class ObjectStorageUploadException extends RuntimeException {

    private final MediaProcessingFailureReason failureReason;

    public ObjectStorageUploadException(MediaProcessingFailureReason failureReason, String message) {
        super(message);
        this.failureReason = failureReason;
    }

    public ObjectStorageUploadException(MediaProcessingFailureReason failureReason, String message, Throwable cause) {
        super(message, cause);
        this.failureReason = failureReason;
    }

    public MediaProcessingFailureReason getFailureReason() {
        return failureReason;
    }
}

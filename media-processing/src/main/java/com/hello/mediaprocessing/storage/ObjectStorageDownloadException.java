package com.hello.mediaprocessing.storage;

import com.hello.mediaprocessing.service.MediaProcessingFailureReason;

public class ObjectStorageDownloadException extends RuntimeException {

    private final MediaProcessingFailureReason failureReason;

    public ObjectStorageDownloadException(MediaProcessingFailureReason failureReason, String message, Throwable cause) {
        super(message, cause);
        this.failureReason = failureReason;
    }

    public ObjectStorageDownloadException(MediaProcessingFailureReason failureReason, String message) {
        super(message);
        this.failureReason = failureReason;
    }

    public MediaProcessingFailureReason getFailureReason() {
        return failureReason;
    }
}

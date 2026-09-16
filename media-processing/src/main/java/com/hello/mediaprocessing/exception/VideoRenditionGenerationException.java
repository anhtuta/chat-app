package com.hello.mediaprocessing.exception;

import com.hello.mediaprocessing.constant.MediaProcessingFailureReason;

/**
 * Signals that a secondary mobile-friendly rendition could not be generated.
 */
public class VideoRenditionGenerationException extends RuntimeException {

    private final MediaProcessingFailureReason failureReason;

    public VideoRenditionGenerationException(MediaProcessingFailureReason failureReason, String message) {
        super(message);
        this.failureReason = failureReason;
    }

    public VideoRenditionGenerationException(MediaProcessingFailureReason failureReason, String message, Throwable cause) {
        super(message, cause);
        this.failureReason = failureReason;
    }

    /**
     * Returns the categorized failure reason for this rendition error.
     *
     * @return normalized rendition failure reason
     */
    public MediaProcessingFailureReason getFailureReason() {
        return failureReason;
    }
}

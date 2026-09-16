package com.hello.mediaprocessing.exception;

import com.hello.mediaprocessing.constant.MediaProcessingFailureReason;

/**
 * Signals that the worker could not generate or upload a poster image for a video.
 */
public class VideoPosterGenerationException extends RuntimeException {

    private final MediaProcessingFailureReason failureReason;

    public VideoPosterGenerationException(MediaProcessingFailureReason failureReason, String message) {
        super(message);
        this.failureReason = failureReason;
    }

    public VideoPosterGenerationException(MediaProcessingFailureReason failureReason, String message, Throwable cause) {
        super(message, cause);
        this.failureReason = failureReason;
    }

    /**
     * Returns the categorized reason for the poster-generation failure.
     *
     * @return normalized failure reason
     */
    public MediaProcessingFailureReason getFailureReason() {
        return failureReason;
    }
}

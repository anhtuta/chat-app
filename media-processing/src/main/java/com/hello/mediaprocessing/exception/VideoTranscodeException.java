package com.hello.mediaprocessing.exception;

import com.hello.mediaprocessing.constant.MediaProcessingFailureReason;

/**
 * Signals that a video could not be converted into the chat playback MP4.
 */
public class VideoTranscodeException extends RuntimeException {

    private final MediaProcessingFailureReason failureReason;

    public VideoTranscodeException(MediaProcessingFailureReason failureReason, String message) {
        super(message);
        this.failureReason = failureReason;
    }

    public VideoTranscodeException(MediaProcessingFailureReason failureReason, String message, Throwable cause) {
        super(message, cause);
        this.failureReason = failureReason;
    }

    public MediaProcessingFailureReason getFailureReason() {
        return failureReason;
    }
}

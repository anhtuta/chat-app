package com.hello.mediaprocessing.exception;

/**
 * Signals that ffprobe-based metadata extraction could not produce a usable video description.
 */
public class VideoMetadataExtractionException extends RuntimeException {

    public VideoMetadataExtractionException(String message) {
        super(message);
    }

    public VideoMetadataExtractionException(String message, Throwable cause) {
        super(message, cause);
    }
}

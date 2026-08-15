package com.hello.mediaprocessing.model;

/**
 * Captures the normalized metadata extracted from a local video source file.
 *
 * @param durationMillis video duration in milliseconds
 * @param width video frame width in pixels
 * @param height video frame height in pixels
 * @param detectedMimeType best-effort MIME type detected from the local file
 * @param containerFormat container name reported by ffprobe
 * @param videoCodec primary video codec name, if present
 * @param audioCodec primary audio codec name, if present
 */
public record VideoMetadata(
        long durationMillis,
        Integer width,
        Integer height,
        String detectedMimeType,
        String containerFormat,
        String videoCodec,
        String audioCodec) {
}

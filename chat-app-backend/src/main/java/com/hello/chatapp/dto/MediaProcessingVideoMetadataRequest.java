package com.hello.chatapp.dto;

/**
 * Video metadata returned by media-processing-service.
 *
 * @param durationMillis duration in milliseconds
 * @param width frame width
 * @param height frame height
 * @param detectedMimeType detected source MIME type
 * @param containerFormat ffprobe container name
 * @param videoCodec video codec name
 * @param audioCodec audio codec name, if present
 */
public record MediaProcessingVideoMetadataRequest(
        long durationMillis,
        Integer width,
        Integer height,
        String detectedMimeType,
        String containerFormat,
        String videoCodec,
        String audioCodec) {
}

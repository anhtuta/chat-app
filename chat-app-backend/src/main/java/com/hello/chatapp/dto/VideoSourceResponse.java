package com.hello.chatapp.dto;

/**
 * Exposes one playable video source to clients.
 *
 * @param url storage-backed playback URL
 * @param mimeType source MIME type
 * @param width source width when known
 * @param height source height when known
 * @param sizeBytes source size in bytes
 * @param role stable source role such as {@code CANONICAL} or {@code MOBILE}
 */
public record VideoSourceResponse(
        String url,
        String mimeType,
        Integer width,
        Integer height,
        Long sizeBytes,
        String role) {
}

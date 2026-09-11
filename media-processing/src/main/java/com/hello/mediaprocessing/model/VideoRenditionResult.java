package com.hello.mediaprocessing.model;

import io.micronaut.serde.annotation.Serdeable;

/**
 * Describes one secondary video rendition produced by the worker.
 *
 * @param objectKey storage key for the rendition
 * @param mimeType rendition MIME type
 * @param width rendition width when known
 * @param height rendition height
 * @param sizeBytes stored object size
 */
@Serdeable
public record VideoRenditionResult(
        String objectKey,
        String mimeType,
        Integer width,
        Integer height,
        Long sizeBytes) {
}

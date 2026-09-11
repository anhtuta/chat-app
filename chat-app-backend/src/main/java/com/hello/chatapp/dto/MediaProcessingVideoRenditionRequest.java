package com.hello.chatapp.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Describes one secondary video rendition returned by media-processing-service.
 *
 * @param objectKey storage key for the rendition
 * @param mimeType rendition MIME type
 * @param width rendition width when known
 * @param height rendition height
 * @param sizeBytes stored object size
 */
public record MediaProcessingVideoRenditionRequest(
        @NotBlank String objectKey,
        @NotBlank String mimeType,
        Integer width,
        @NotNull @Positive Integer height,
        @NotNull @Positive Long sizeBytes) {
}

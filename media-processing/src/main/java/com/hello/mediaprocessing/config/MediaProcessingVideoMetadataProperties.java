package com.hello.mediaprocessing.config;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.core.annotation.Introspected;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/**
 * Binds the ffprobe command settings used for video metadata extraction.
 */
@ConfigurationProperties("media-processing.video-metadata")
@Introspected
@Getter
@Setter
public class MediaProcessingVideoMetadataProperties {

    @NotBlank
    private String ffprobePath = "ffprobe";

    @Min(1)
    private int timeoutSeconds = 30;
}

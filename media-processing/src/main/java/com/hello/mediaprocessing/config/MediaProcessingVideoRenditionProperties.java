package com.hello.mediaprocessing.config;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.core.annotation.Introspected;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/**
 * Binds ffmpeg and rollout settings for secondary mobile-friendly MP4 renditions.
 */
@ConfigurationProperties("media-processing.video-rendition")
@Introspected
@Getter
@Setter
public class MediaProcessingVideoRenditionProperties {

    @NotBlank
    private String ffmpegPath = "ffmpeg";

    @Min(1)
    private int timeoutSeconds = 180;

    @Min(0)
    private int videoCrf = 26;

    @NotBlank
    private String preset = "veryfast";

    @NotBlank
    private String audioBitrate = "96k";

    @Min(1)
    private int maxHeight = 480;

    @Min(0)
    private int minDurationSeconds = 15;

    @Min(0)
    private long minCanonicalSizeBytes = 8L * 1024 * 1024;
}

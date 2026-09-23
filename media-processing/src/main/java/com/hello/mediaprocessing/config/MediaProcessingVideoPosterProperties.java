package com.hello.mediaprocessing.config;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.core.annotation.Introspected;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/**
 * Binds ffmpeg settings used to extract a poster frame from a processed video.
 */
@ConfigurationProperties("media-processing.video-poster")
@Introspected
@Getter
@Setter
public class MediaProcessingVideoPosterProperties {

    @NotBlank
    private String ffmpegPath = "ffmpeg";

    @Min(1)
    private int timeoutSeconds = 60;

    @DecimalMin("0.0")
    private double captureSecond = 1.0d;

    @Min(1)
    private int maxWidth = 1280;

    @Min(2)
    private int jpegQuality = 2;
}

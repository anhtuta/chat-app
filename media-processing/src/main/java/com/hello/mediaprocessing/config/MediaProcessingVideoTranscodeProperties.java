package com.hello.mediaprocessing.config;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.core.annotation.Introspected;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/**
 * Binds the ffmpeg settings used to produce a chat-friendly playback MP4.
 */
@ConfigurationProperties("media-processing.video-transcode")
@Introspected
@Getter
@Setter
public class MediaProcessingVideoTranscodeProperties {

    @NotBlank
    private String ffmpegPath = "ffmpeg";

    @Min(1)
    private int timeoutSeconds = 300;

    @Min(1)
    private int videoCrf = 23;

    @NotBlank
    private String preset = "veryfast";

    @NotBlank
    private String audioBitrate = "128k";
}

package com.hello.mediaprocessing.config;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.core.annotation.Introspected;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/**
 * Binds the optional local HTTP trigger used to exercise transcode without chat-app-backend.
 */
@ConfigurationProperties("media-processing.local-trigger")
@Introspected
@Getter
@Setter
public class MediaProcessingLocalTriggerProperties {

    private boolean enabled = false;

    @NotBlank
    private String defaultBucket = "chat-media";
}

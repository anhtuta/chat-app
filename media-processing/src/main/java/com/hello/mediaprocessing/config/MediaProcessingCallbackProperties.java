package com.hello.mediaprocessing.config;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.core.annotation.Introspected;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/**
 * Binds the chat-backend callback endpoint and service credential.
 */
@ConfigurationProperties("media-processing.callback")
@Introspected
@Getter
@Setter
public class MediaProcessingCallbackProperties {

    private boolean enabled = false;

    @NotBlank
    private String baseUrl = "http://localhost:9010";

    @NotBlank
    private String token = "change-me-in-production";
}

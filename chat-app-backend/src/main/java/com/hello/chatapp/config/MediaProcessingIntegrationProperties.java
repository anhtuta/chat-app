package com.hello.chatapp.config;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Binds RabbitMQ job publishing and internal callback authentication settings.
 */
@ConfigurationProperties(prefix = "chat.media.processing")
@Validated
@Getter
@Setter
public class MediaProcessingIntegrationProperties {

    private boolean enabled = false;

    @NotBlank
    private String exchange = "media.processing";

    @NotBlank
    private String queue = "media.processing.jobs";

    @NotBlank
    private String routingKey = "media.processing.video";

    @NotBlank
    private String callbackToken = "change-me-in-production";
}

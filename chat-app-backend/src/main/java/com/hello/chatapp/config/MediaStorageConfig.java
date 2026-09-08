package com.hello.chatapp.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers media storage and processing-integration configuration.
 */
@Configuration
@EnableConfigurationProperties({
        MediaStorageProperties.class,
        MediaProcessingIntegrationProperties.class
})
public class MediaStorageConfig {
}

package com.hello.chatapp.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Register configuration properties for media storage.
 */
@Configuration
@EnableConfigurationProperties(MediaStorageProperties.class)
public class MediaStorageConfig {
}

package com.hello.chatapp.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.lang.NonNull;

/**
 * Should NOT print sensitive information like password in production.
 */
public class EarlyPropertyPrinter implements ApplicationContextInitializer<ConfigurableApplicationContext> {
    private static final Logger log = LoggerFactory.getLogger(EarlyPropertyPrinter.class);

    @Override
    public void initialize(@NonNull ConfigurableApplicationContext applicationContext) {
        ConfigurableEnvironment env = applicationContext.getEnvironment();

        log.info("=================================================");
        log.info("EARLY DIAGNOSTIC PROPERTY CHECK:");
        log.info("spring.datasource.url: {}", env.getProperty("spring.datasource.url"));
        log.info("spring.datasource.username: {}", env.getProperty("spring.datasource.username"));
        // log.info("spring.datasource.password (EVALUATED): {}", env.getProperty("spring.datasource.password"));

        log.info("chat.media.minio.endpoint: {}", env.getProperty("chat.media.minio.endpoint"));
        log.info("chat.media.minio.access-key: {}", env.getProperty("chat.media.minio.access-key"));
        log.info("chat.media.minio.bucket: {}", env.getProperty("chat.media.minio.bucket"));
        log.info("chat.media.minio.region: {}", env.getProperty("chat.media.minio.region"));
        log.info("=================================================");
    }
}

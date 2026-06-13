package com.hello.chatapp.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Should NOT print sensitive information like password in production.
 */
public class EarlyPropertyPrinter implements ApplicationContextInitializer<ConfigurableApplicationContext> {
    private static final Logger log = LoggerFactory.getLogger(EarlyPropertyPrinter.class);

    @Override
    public void initialize(ConfigurableApplicationContext applicationContext) {
        ConfigurableEnvironment env = applicationContext.getEnvironment();

        log.info("=================================================");
        log.info("EARLY DIAGNOSTIC PROPERTY CHECK:");
        log.info("spring.datasource.url: {}", env.getProperty("spring.datasource.url"));
        log.info("spring.datasource.username: {}", env.getProperty("spring.datasource.username"));
        // log.info("spring.datasource.password (EVALUATED): {}", env.getProperty("spring.datasource.password"));
        log.info("=================================================");
    }
}

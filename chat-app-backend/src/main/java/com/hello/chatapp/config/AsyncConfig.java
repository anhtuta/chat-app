package com.hello.chatapp.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
public class AsyncConfig {

    @Bean(name = "groupSummaryUpdateScheduler")
    public ThreadPoolTaskScheduler groupSummaryUpdateScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setThreadNamePrefix("group-summary-buffer-");
        scheduler.setPoolSize(4);
        scheduler.initialize();
        return scheduler;
    }
}

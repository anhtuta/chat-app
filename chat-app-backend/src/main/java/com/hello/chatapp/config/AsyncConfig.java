package com.hello.chatapp.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "groupSummaryUpdateScheduler")
    public ThreadPoolTaskScheduler groupSummaryUpdateScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setThreadNamePrefix("group-summary-buffer-");
        scheduler.setPoolSize(4);
        scheduler.initialize();
        return scheduler;
    }

    @Bean(name = "mediaProcessingExecutor")
    public ThreadPoolTaskExecutor mediaProcessingExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("media-processing-");
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(200);
        executor.initialize();
        return executor;
    }
}

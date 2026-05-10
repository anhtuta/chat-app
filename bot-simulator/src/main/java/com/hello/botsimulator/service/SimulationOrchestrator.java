package com.hello.botsimulator.service;

import com.hello.botsimulator.client.ChatHttpSessionClient;
import com.hello.botsimulator.config.SimulatorProperties;
import com.hello.botsimulator.model.SimulationStats;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;

@Component
public class SimulationOrchestrator implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(SimulationOrchestrator.class);

    private final SimulatorProperties properties;
    private final ChatHttpSessionClient httpSessionClient;
    private final WebSocketStompClient webSocketStompClient;

    private final AtomicBoolean running = new AtomicBoolean(true);
    private final SimulationStats stats = new SimulationStats();

    private ExecutorService botExecutor;
    private ScheduledExecutorService reporterExecutor;
    private Instant startedAt;

    public SimulationOrchestrator(SimulatorProperties properties,
            ChatHttpSessionClient httpSessionClient,
            WebSocketStompClient webSocketStompClient) {
        this.properties = properties;
        this.httpSessionClient = httpSessionClient;
        this.webSocketStompClient = webSocketStompClient;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        startedAt = Instant.now();

        List<String> botUsernames = IntStream.rangeClosed(1, properties.getBotCount())
                .mapToObj(this::botUsername)
                .toList();

        List<Long> targetGroupIds = resolveTargetGroupIds();

        botExecutor = Executors.newVirtualThreadPerTaskExecutor();
        reporterExecutor = Executors.newSingleThreadScheduledExecutor();

        startReporter();
        startBots(botUsernames, targetGroupIds);

        logger.info("Simulator started with {} bots targeting group IDs {}", properties.getBotCount(), targetGroupIds);
    }

    private void startReporter() {
        reporterExecutor.scheduleAtFixedRate(
                this::reportStats,
                properties.getReportIntervalSeconds(),
                properties.getReportIntervalSeconds(),
                TimeUnit.SECONDS);
    }

    private void startBots(List<String> botUsernames, List<Long> targetGroupIds) {
        for (int i = 0; i < botUsernames.size(); i++) {
            int botNumber = i + 1;
            String username = botUsernames.get(i);
            StompBotWorker worker = new StompBotWorker(
                    botNumber,
                    username,
                    properties.getBotPassword(),
                    targetGroupIds,
                    properties,
                    httpSessionClient,
                    webSocketStompClient,
                    stats,
                    running);
            botExecutor.submit(worker);
        }
    }

    private List<Long> resolveTargetGroupIds() {
        if (properties.getTargetGroupIds() == null || properties.getTargetGroupIds().isEmpty()) {
            throw new IllegalArgumentException("simulator.target-group-ids must contain at least one group ID");
        }

        return List.copyOf(properties.getTargetGroupIds());
    }

    private void reportStats() {
        long runtimeSeconds = Duration.between(startedAt, Instant.now()).toSeconds();
        logger.info(
                "[runtime={}s] connectedBots={} sentMessages={} failedMessages={} connectFailures={}",
                runtimeSeconds,
                stats.connectedBots(),
                stats.sentMessages(),
                stats.failedMessages(),
                stats.connectFailures());
    }

    private String botUsername(int idx) {
        return properties.getBotUsernamePrefix() + idx;
    }

    @PreDestroy
    public void shutdown() {
        running.set(false);

        if (reporterExecutor != null) {
            reporterExecutor.shutdownNow();
        }

        if (botExecutor != null) {
            botExecutor.shutdownNow();
        }

        webSocketStompClient.stop();
        logger.info("Bot simulator stopped");
    }
}

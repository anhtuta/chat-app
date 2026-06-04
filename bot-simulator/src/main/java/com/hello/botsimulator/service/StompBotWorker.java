package com.hello.botsimulator.service;

import com.github.javafaker.Faker;
import com.hello.botsimulator.client.ChatHttpSessionClient;
import com.hello.botsimulator.config.SimulatorProperties;
import com.hello.botsimulator.model.BotHttpSession;
import com.hello.botsimulator.model.SimulationStats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.stomp.ConnectionLostException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class StompBotWorker implements Runnable {

    private static final Logger logger = LoggerFactory.getLogger(StompBotWorker.class);

    private final int botNumber;
    private final String username;
    private final String password;
    private final List<Long> groupIds;
    private final SimulatorProperties properties;
    private final ChatHttpSessionClient httpSessionClient;
    private final WebSocketStompClient webSocketStompClient;
    private final SimulationStats stats;
    private final AtomicBoolean running;
    private final Faker faker;

    public StompBotWorker(int botNumber,
            String username,
            String password,
            List<Long> groupIds,
            SimulatorProperties properties,
            ChatHttpSessionClient httpSessionClient,
            WebSocketStompClient webSocketStompClient,
            SimulationStats stats,
            AtomicBoolean running) {
        this.botNumber = botNumber;
        this.username = username;
        this.password = password;
        this.groupIds = groupIds;
        this.properties = properties;
        this.httpSessionClient = httpSessionClient;
        this.webSocketStompClient = webSocketStompClient;
        this.stats = stats;
        this.running = running;
        this.faker = new Faker();
    }

    @Override
    public void run() {
        sleepStartupJitter();

        while (running.get() && !Thread.currentThread().isInterrupted()) {
            StompSession session = null;
            try {
                BotHttpSession httpSession = httpSessionClient.login(
                        properties.getBaseUrl(),
                        username,
                        password,
                        Duration.ofSeconds(properties.getConnectTimeoutSeconds()));

                session = connect(httpSession.cookieHeader());
                stats.incrementConnectedBots();

                sendLoop(session);
            } catch (Exception ex) {
                stats.incrementConnectFailures();
                logger.debug("Bot {} failed to connect/send: {}", username, ex.getMessage());
                sleepQuietly(properties.getReconnectDelayMs());
            } finally {
                if (session != null && session.isConnected()) {
                    try {
                        session.disconnect();
                    } catch (Throwable ignored) {
                        // Ignore disconnect exceptions while shutting down/reconnecting.
                    }
                    stats.decrementConnectedBots();
                }
            }
        }
    }

    private StompSession connect(String cookieHeader) throws Exception {
        CompletableFuture<StompSession> sessionFuture = new CompletableFuture<>();
        String wsUrl = properties.getBaseUrl() + properties.getWsEndpoint();

        WebSocketHttpHeaders webSocketHttpHeaders = new WebSocketHttpHeaders();
        webSocketHttpHeaders.add("Cookie", cookieHeader);

        StompHeaders connectHeaders = new StompHeaders();

        webSocketStompClient.connectAsync(
                wsUrl,
                webSocketHttpHeaders,
                connectHeaders,
                new StompSessionHandlerAdapter() {
                    @Override
                    public void afterConnected(StompSession session, StompHeaders connectedHeaders) {
                        sessionFuture.complete(session);
                        subscribeNoop(session);
                    }

                    @Override
                    public void handleException(StompSession session,
                            StompCommand command,
                            StompHeaders headers,
                            byte[] payload,
                            Throwable exception) {
                        if (!sessionFuture.isDone()) {
                            sessionFuture.completeExceptionally(exception);
                        }
                    }

                    @Override
                    public void handleTransportError(StompSession session, Throwable exception) {
                        if (!sessionFuture.isDone()) {
                            sessionFuture.completeExceptionally(exception);
                        }
                    }
                });

        return sessionFuture.get(properties.getConnectTimeoutSeconds(), TimeUnit.SECONDS);
    }

    private void sendLoop(StompSession session) {
        while (running.get() && session.isConnected() && !Thread.currentThread().isInterrupted()) {
            try {
                Long groupId = pickRandomGroupId();
                session.send("/app/group.send", buildMessagePayload(groupId));
                stats.incrementSentMessages();
                sleepQuietly(nextSendDelayMs());
            } catch (ConnectionLostException ex) {
                throw ex;
            } catch (Exception ex) {
                stats.incrementFailedMessages();
                logger.debug("Bot {} failed to send message: {}", username, ex.getMessage());
                sleepQuietly(properties.getReconnectDelayMs());
            }
        }
    }

    private void subscribeNoop(StompSession session) {
        for (Long groupId : groupIds) {
            String destination = "/topic/group." + groupId;
            session.subscribe(destination, new StompFrameHandler() {
                @Override
                public Type getPayloadType(StompHeaders headers) {
                    return byte[].class;
                }

                @Override
                public void handleFrame(StompHeaders headers, Object payload) {
                    // The simulator sends load traffic; consuming responses is optional.
                }
            });
        }
    }

    private Map<String, Object> buildMessagePayload(Long groupId) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("groupId", groupId);
        payload.put("content", properties.getMessagePrefix() + " | bot=" + botNumber + " | " + hackerSentence());
        return payload;
    }

    private String hackerSentence() {
        return faker.hacker().ingverb() + " " + faker.hacker().adjective() + " " + faker.hacker().noun();
    }

    private Long pickRandomGroupId() {
        int index = ThreadLocalRandom.current().nextInt(groupIds.size());
        return groupIds.get(index);
    }

    private long nextSendDelayMs() {
        long base = properties.getSendIntervalMs();
        long jitter = properties.getSendJitterMs();
        if (jitter <= 0) {
            return base;
        }
        return base + ThreadLocalRandom.current().nextLong(jitter + 1);
    }

    private void sleepStartupJitter() {
        long spread = properties.getStartupSpreadMs();
        if (spread <= 0) {
            return;
        }
        long delay = ThreadLocalRandom.current().nextLong(spread + 1);
        sleepQuietly(delay);
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

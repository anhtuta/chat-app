package com.hello.chatapp.service;

import com.hello.chatapp.config.CustomRabbitMQBrokerHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * Dual-publishes chat payloads to local STOMP subscribers and RabbitMQ for cross-instance delivery.
 * <p>
 * Callers that previously duplicated {@code convertAndSend} + {@code publishToRabbitMQ} should use this
 * instead (group chat sends, membership system lines, media publish/republish, etc.).
 */
@Service
public class RealtimeMessageDeliveryService {

    private static final Logger logger = LoggerFactory.getLogger(RealtimeMessageDeliveryService.class);

    private final SimpMessagingTemplate messagingTemplate;
    private final CustomRabbitMQBrokerHandler rabbitMQBrokerHandler;

    public RealtimeMessageDeliveryService(
            SimpMessagingTemplate messagingTemplate,
            CustomRabbitMQBrokerHandler rabbitMQBrokerHandler) {
        this.messagingTemplate = messagingTemplate;
        this.rabbitMQBrokerHandler = rabbitMQBrokerHandler;
    }

    /**
     * Sends {@code payload} to local SimpleBroker subscribers, then publishes the same payload to RabbitMQ.
     */
    public void publish(String destination, Object payload) {
        String safeDestination = Objects.requireNonNull(destination, "destination must not be null");
        Object safePayload = Objects.requireNonNull(payload, "payload must not be null");

        logger.debug("[publish] Sending to in-memory broker for local subscribers: destination={}", safeDestination);
        messagingTemplate.convertAndSend(safeDestination, safePayload);

        logger.debug("[publish] Publishing to RabbitMQ for cross-instance distribution: destination={}", safeDestination);
        rabbitMQBrokerHandler.publishToRabbitMQ(safeDestination, safePayload);
    }

    /**
     * Publishes to {@code /topic/group.{groupId}}.
     */
    public void publishToGroup(Long groupId, Object payload) {
        Long safeGroupId = Objects.requireNonNull(groupId, "groupId must not be null");
        publish("/topic/group." + safeGroupId, payload);
    }

    /**
     * Publishes to {@code /topic/public}.
     */
    public void publishToPublic(Object payload) {
        publish("/topic/public", payload);
    }
}

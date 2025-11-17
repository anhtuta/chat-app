package com.hello.chatapp.processor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.hello.chatapp.config.CustomRabbitMQBrokerHandler;

/**
 * Processes messages received from RabbitMQ queues.
 * 
 * This component handles:
 * - Checking if message came from same instance (to avoid duplicates)
 * - Deserializing message payload
 * - Forwarding to local WebSocket subscribers
 * 
 * Separated from DynamicRabbitMQListener to avoid circular dependency:
 * - DynamicRabbitMQListener -> RabbitMQMessageProcessor
 * - RabbitMQMessageProcessor -> CustomRabbitMQBrokerHandler
 * - CustomRabbitMQBrokerHandler -> DynamicRabbitMQListener (with @Lazy)
 */
@Component
public class RabbitMQMessageProcessor {

    private static final Logger logger = LoggerFactory.getLogger(RabbitMQMessageProcessor.class);

    private final MessageConverter messageConverter;
    private final CustomRabbitMQBrokerHandler brokerHandler;

    @Value("${spring.application.instance-id:${random.uuid}}")
    private String instanceId;

    public RabbitMQMessageProcessor(MessageConverter messageConverter,
            CustomRabbitMQBrokerHandler brokerHandler) {
        this.messageConverter = messageConverter;
        this.brokerHandler = brokerHandler;
    }

    /**
     * Processes a message received from RabbitMQ.
     * 
     * @param message The RabbitMQ message
     * @param destination The STOMP destination (e.g., "/topic/public", "/topic/group.1")
     * @param queueName The queue name (for logging)
     * @return true if message was processed, false if skipped (e.g., from same instance)
     */
    public boolean processMessage(Message message, String destination, String queueName) {
        try {
            // Check if message came from this instance (skip to avoid duplicate)
            String sourceInstanceId = (String) message.getMessageProperties().getHeaders().get("source-instance-id");
            if (instanceId.equals(sourceInstanceId)) {
                logger.debug("Skipping message from same instance: queue={}, instanceId={}", queueName, instanceId);
                return false;
            }

            // Deserialize payload using the configured MessageConverter
            Object payload = messageConverter.fromMessage(message);

            logger.info("Received message from queue: {}, destination: {}, instanceId: {}", queueName, destination, instanceId);

            // Forward to local subscribers
            brokerHandler.forwardToLocalSubscribers(destination, payload);
            return true;
        } catch (Exception e) {
            logger.error("Error processing message from queue: {}", queueName, e);
            return false;
        }
    }
}


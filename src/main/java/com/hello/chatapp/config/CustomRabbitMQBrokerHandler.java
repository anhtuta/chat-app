package com.hello.chatapp.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.*;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.hello.chatapp.listener.DynamicRabbitMQListener;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Custom RabbitMQ broker handler that syncs subscriptions to RabbitMQ
 * and handles cross-instance message distribution.
 * This works alongside Spring's SimpleBroker for local WebSocket connections.
 */
@Component
public class CustomRabbitMQBrokerHandler {

    private static final Logger logger = LoggerFactory.getLogger(CustomRabbitMQBrokerHandler.class);

    // Single exchange name for all destinations
    private static final String SINGLE_EXCHANGE_NAME = "chat.exchange";

    // Single queue name for this instance (shared by all destinations)
    private String instanceQueueName;

    // Track which destinations this instance is subscribed to
    // destination -> subscription count (number of users subscribed to this destination)
    private final ConcurrentHashMap<String, Integer> destinationSubscriptionCount = new ConcurrentHashMap<>();

    // Track if the queue has been created and listener started
    private boolean queueInitialized = false;

    private final RabbitTemplate rabbitTemplate;

    private final AmqpAdmin amqpAdmin;

    private final DynamicRabbitMQListener dynamicListener;

    @Value("${spring.application.instance-id:${random.uuid}}")
    private String instanceId;

    public CustomRabbitMQBrokerHandler(RabbitTemplate rabbitTemplate, AmqpAdmin amqpAdmin,
            DynamicRabbitMQListener dynamicListener) {
        this.rabbitTemplate = rabbitTemplate;
        this.amqpAdmin = amqpAdmin;
        this.dynamicListener = dynamicListener;
    }

    @PostConstruct
    public void init() {
        logger.info("CustomRabbitMQBrokerHandler initialized for instance: {}", instanceId);
        // Create single queue for this instance
        instanceQueueName = "ws." + instanceId;
        ensureQueueAndExchangeExist();
    }

    @PreDestroy
    public void cleanup() {
        logger.info("Cleaning up RabbitMQ subscriptions for instance: {}", instanceId);
        // Clean up all queues created by this instance
        cleanupAllQueues();
    }

    /**
     * Handles subscription - syncs to RabbitMQ
     */
    public void handleSubscribe(String destination) {
        logger.debug("Handling subscribe: destination={}, instanceId={}", destination, instanceId);

        // Sync subscription to RabbitMQ
        syncSubscriptionToRabbitMQ(destination, true);
    }

    /**
     * Handles unsubscription - removes from RabbitMQ
     */
    public void handleUnsubscribe(String destination) {
        logger.debug("Handling unsubscribe: destination={}, instanceId={}", destination, instanceId);

        // Sync unsubscription to RabbitMQ
        syncSubscriptionToRabbitMQ(destination, false);
    }

    /**
     * Checks if this instance is subscribed to a destination.
     * Used by the listener to filter messages.
     */
    public boolean isSubscribedToDestination(String destination) {
        Integer count = destinationSubscriptionCount.get(destination);
        return count != null && count > 0;
    }

    /**
     * Publishes message to RabbitMQ for cross-instance distribution.
     * Uses single FanoutExchange: all messages go to one exchange, broadcasts to all instance queues.
     * Destination is included in message headers for filtering on consumer side.
     */
    public void publishToRabbitMQ(String destination, Object payload) {
        try {
            logger.debug("Publishing to RabbitMQ: from instance={} to exchange={}, destination={}",
                    instanceId, SINGLE_EXCHANGE_NAME, destination);

            // Ensure single exchange and queue exist
            ensureQueueAndExchangeExist();

            // Convert payload to message and add headers
            Message message = rabbitTemplate.getMessageConverter().toMessage(payload, new MessageProperties());
            message.getMessageProperties().setHeader("source-instance-id", instanceId);
            message.getMessageProperties().setHeader("destination", destination); // Include destination for filtering

            // Publish to single FanoutExchange (routing key is ignored for FanoutExchange)
            // FanoutExchange broadcasts messages to all bound instance queues
            rabbitTemplate.send(SINGLE_EXCHANGE_NAME, "", message);
        } catch (Exception e) {
            logger.error("Error publishing to RabbitMQ for destination: {}", destination, e);
        }
    }

    /**
     * Cleans up the single queue created by this instance (called on shutdown)
     */
    private void cleanupAllQueues() {
        if (queueInitialized && instanceQueueName != null) {
            logger.info("Cleaning up queue for instance: {}", instanceId);
            try {
                // Stop listener if exists
                if (dynamicListener != null) {
                    dynamicListener.stopListening(instanceQueueName);
                }
                // Delete queue (bindings are automatically removed)
                amqpAdmin.deleteQueue(instanceQueueName);
                logger.debug("Deleted queue: {}", instanceQueueName);
            } catch (Exception e) {
                logger.warn("Error deleting queue {}: {}", instanceQueueName, e.getMessage());
            }
            queueInitialized = false;
            destinationSubscriptionCount.clear();
        }
    }

    /**
     * Syncs subscription to RabbitMQ.
     * Uses single FanoutExchange: one exchange for all destinations, one queue per instance.
     * Queue is created once on first subscription and reused for all destinations.
     * We only track which destinations this instance is subscribed to (for filtering).
     */
    private void syncSubscriptionToRabbitMQ(String destination, boolean subscribe) {
        try {
            // Ensure single exchange and queue exist
            ensureQueueAndExchangeExist();

            if (subscribe) {
                // Increment subscription count for this destination
                int count = destinationSubscriptionCount.compute(destination, (k, v) -> (v == null) ? 1 : v + 1);
                logger.debug("Subscribed to destination: {}, count: {}", destination, count);
            } else {
                // Decrement subscription count for this destination
                int count = destinationSubscriptionCount.compute(destination, (k, v) -> (v == null || v <= 1) ? 0 : v - 1);
                logger.debug("Unsubscribed from destination: {}, count: {}", destination, count);

                // Remove from tracking if no more subscriptions
                if (count == 0) {
                    destinationSubscriptionCount.remove(destination);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Error syncing subscription to RabbitMQ: destination=" + destination, e);
        }
    }

    /**
     * Ensures the single exchange and instance queue exist in RabbitMQ.
     * Creates them once and reuses for all destinations.
     */
    private void ensureQueueAndExchangeExist() {
        if (queueInitialized) {
            return; // Already initialized
        }

        try {
            // Create single FanoutExchange for all destinations
            logger.debug("Creating single FanoutExchange: {}", SINGLE_EXCHANGE_NAME);
            FanoutExchange fanoutExchange = new FanoutExchange(SINGLE_EXCHANGE_NAME, true, false);
            amqpAdmin.declareExchange(fanoutExchange);

            // Create single queue for this instance
            logger.debug("Creating single queue for instance: {}", instanceQueueName);
            Queue queue = QueueBuilder.durable(instanceQueueName).build();
            amqpAdmin.declareQueue(queue);

            // Bind queue to exchange (no routing key needed for FanoutExchange)
            amqpAdmin.declareBinding(BindingBuilder.bind(queue).to(fanoutExchange));
            logger.debug("Bound queue {} to exchange {}", instanceQueueName, SINGLE_EXCHANGE_NAME);

            // Start listening to this queue (single listener for all destinations)
            if (dynamicListener != null) {
                dynamicListener.startListening(instanceQueueName, null); // destination=null means filter by header
            }

            queueInitialized = true;
            logger.info("Initialized single queue and exchange for instance: {}", instanceId);
        } catch (Exception e) {
            throw new RuntimeException("Error creating queue and exchange for instance: " + instanceId, e);
        }
    }
}

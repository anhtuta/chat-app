package com.hello.chatapp.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.*;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Custom RabbitMQ broker handler that syncs subscriptions to RabbitMQ
 * and handles cross-instance message distribution.
 * 
 * This works alongside Spring's SimpleBroker for local WebSocket connections.
 */
@Component
public class CustomRabbitMQBrokerHandler {

    private static final Logger logger = LoggerFactory.getLogger(CustomRabbitMQBrokerHandler.class);

    // Track local subscriptions: destination -> Set<sessionId>
    // This is in addition to SimpleBroker's internal tracking
    // (In-memory broker uses another ConcurrentHashMap for local WebSocket connections)
    private final ConcurrentHashMap<String, Set<String>> localSubscriptions = new ConcurrentHashMap<>();

    // Track all queues created by this instance: sessionId -> Set<queueName>
    // Used for cleanup on disconnect and shutdown
    private final ConcurrentHashMap<String, Set<String>> sessionQueues = new ConcurrentHashMap<>();

    private final RabbitTemplate rabbitTemplate;

    private final AmqpAdmin amqpAdmin;

    @Autowired(required = false)
    @Lazy
    private DynamicRabbitMQListener dynamicListener;

    // Use setter injection to break circular dependency with SimpMessagingTemplate
    private SimpMessagingTemplate messagingTemplate;

    @Value("${spring.application.instance-id:${random.uuid}}")
    private String instanceId;

    public CustomRabbitMQBrokerHandler(RabbitTemplate rabbitTemplate, AmqpAdmin amqpAdmin) {
        this.rabbitTemplate = rabbitTemplate;
        this.amqpAdmin = amqpAdmin;
    }

    /**
     * Setter injection for SimpMessagingTemplate to break circular dependency.
     * This is called after all beans are created, avoiding the circular dependency issue.
     */
    @Autowired
    @Lazy
    public void setMessagingTemplate(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    @PostConstruct
    public void init() {
        logger.info("CustomRabbitMQBrokerHandler initialized for instance: {}", instanceId);
    }

    @PreDestroy
    public void cleanup() {
        logger.info("Cleaning up RabbitMQ subscriptions for instance: {}", instanceId);
        // Clean up all queues created by this instance
        cleanupAllQueues();
    }

    /**
     * Cleans up all queues for a specific session (called on WebSocket disconnect)
     */
    public void cleanupSessionQueues(String sessionId) {
        Set<String> queues = sessionQueues.remove(sessionId);
        if (queues != null && !queues.isEmpty()) {
            logger.info("Cleaning up {} queues for session: {}, instanceId: {}",
                    queues.size(), sessionId, instanceId);
            for (String queueName : queues) {
                try {
                    // Stop listener if exists
                    if (dynamicListener != null) {
                        dynamicListener.stopListening(queueName);
                    }
                    // Delete queue (bindings are automatically removed)
                    amqpAdmin.deleteQueue(queueName);
                    logger.debug("Deleted queue: {}", queueName);
                } catch (Exception e) {
                    logger.warn("Error deleting queue {}: {}", queueName, e.getMessage());
                }
            }
        }
    }

    /**
     * Handles subscription - syncs to RabbitMQ and tracks locally
     */
    public void handleSubscribe(String sessionId, String destination) {
        logger.debug("Handling subscribe: sessionId={}, destination={}, instanceId={}", sessionId, destination, instanceId);

        // Track locally
        localSubscriptions.computeIfAbsent(destination, k -> ConcurrentHashMap.newKeySet()).add(sessionId);

        // Sync subscription to RabbitMQ
        syncSubscriptionToRabbitMQ(destination, sessionId, true);
    }

    /**
     * Handles unsubscription - removes from RabbitMQ and local tracking
     */
    public void handleUnsubscribe(String sessionId, String destination) {
        logger.debug("Handling unsubscribe: sessionId={}, destination={}, instanceId={}",
                sessionId, destination, instanceId);

        // Remove from local tracking
        Set<String> subs = localSubscriptions.get(destination);
        if (subs != null) {
            subs.remove(sessionId);
            if (subs.isEmpty()) {
                localSubscriptions.remove(destination);
            }
        }

        // Sync unsubscription to RabbitMQ
        syncSubscriptionToRabbitMQ(destination, sessionId, false);
    }

    /**
     * Publishes message to RabbitMQ for cross-instance distribution.
     * Uses DirectExchange: one exchange per destination, no routing keys needed.
     */
    public void publishToRabbitMQ(String destination, Object payload) {
        try {
            String exchange = convertDestinationToExchange(destination);

            logger.debug("Publishing to RabbitMQ: exchange={}, instanceId={}",
                    exchange, instanceId);

            // Ensure exchange exists (DirectExchange, one per destination)
            ensureExchangeExists(exchange);

            // Convert payload to message and add instance ID header
            Message message = rabbitTemplate.getMessageConverter().toMessage(payload, new MessageProperties());
            message.getMessageProperties().setHeader("source-instance-id", instanceId);

            // Publish to DirectExchange with empty routing key
            // DirectExchange routes messages to queues bound with matching routing key
            // Since we use empty routing key, all queues bound to this exchange receive the message
            rabbitTemplate.send(exchange, "", message);
        } catch (Exception e) {
            logger.error("Error publishing to RabbitMQ for destination: {}", destination, e);
        }
    }

    /**
     * Checks if this instance has local subscribers for a destination
     */
    public boolean hasLocalSubscribers(String destination) {
        Set<String> subs = localSubscriptions.get(destination);
        return subs != null && !subs.isEmpty();
    }

    /**
     * Forwards message to local WebSocket subscribers.
     * 
     * This is REQUIRED for cross-instance messaging:
     * - When a message comes from RabbitMQ (from another instance),
     * DynamicRabbitMQListener calls this method
     * - This method uses SimpMessagingTemplate to deliver to SimpleBroker,
     * which then delivers to local WebSocket subscribers
     * 
     * Without this, messages from other instances would be received from RabbitMQ
     * but never delivered to local WebSocket clients.
     */
    public void forwardToLocalSubscribers(String destination, Object payload) {
        logger.debug("[forwardToLocalSubscribers] Forwarding to local subscribers: destination={}, instanceId={}",
                destination, instanceId);
        if (hasLocalSubscribers(destination) && messagingTemplate != null) {
            logger.debug("Forwarding to local subscribers: destination={}, instanceId={}",
                    destination, instanceId);
            messagingTemplate.convertAndSend(destination, payload);
        }
    }

    /**
     * Cleans up all queues created by this instance (called on shutdown)
     */
    private void cleanupAllQueues() {
        int totalQueues = 0;
        for (Set<String> queues : sessionQueues.values()) {
            totalQueues += queues.size();
        }

        if (totalQueues > 0) {
            logger.info("Cleaning up {} queues for instance: {}", totalQueues, instanceId);
            for (Set<String> queues : sessionQueues.values()) {
                for (String queueName : queues) {
                    try {
                        // Stop listener if exists
                        if (dynamicListener != null) {
                            dynamicListener.stopListening(queueName);
                        }
                        // Delete queue
                        amqpAdmin.deleteQueue(queueName);
                        logger.debug("Deleted queue: {}", queueName);
                    } catch (Exception e) {
                        logger.warn("Error deleting queue {}: {}", queueName, e.getMessage());
                    }
                }
            }
            sessionQueues.clear();
        }
    }

    /**
     * Syncs subscription to RabbitMQ by creating/removing queue bindings.
     * Uses DirectExchange: one exchange per destination, empty routing key.
     */
    private void syncSubscriptionToRabbitMQ(String destination, String sessionId, boolean subscribe) {
        try {
            String exchange = convertDestinationToExchange(destination);
            String queueName = createQueueName(instanceId, sessionId, destination);

            // Ensure exchange exists (DirectExchange, one per destination)
            ensureExchangeExists(exchange);

            if (subscribe) {
                // Create queue and bind to DirectExchange with empty routing key
                Queue queue = QueueBuilder.durable(queueName).build();
                amqpAdmin.declareQueue(queue);
                // TODO why empty routing key? why don't use fanout instead?
                amqpAdmin.declareBinding(BindingBuilder.bind(queue).to(new DirectExchange(exchange)).with(""));
                logger.debug("Created RabbitMQ queue and binding: queue={}, exchange={}", queueName, exchange);

                // Track this queue for cleanup
                sessionQueues.computeIfAbsent(sessionId, k -> ConcurrentHashMap.newKeySet()).add(queueName);

                // Start listening to this queue dynamically
                // This handles both public and group messages via per-subscription queues
                if (dynamicListener != null) {
                    dynamicListener.startListening(queueName, destination);
                }
            } else {
                // Stop listening to this queue
                if (dynamicListener != null) {
                    dynamicListener.stopListening(queueName);
                }

                // Remove from tracking
                Set<String> queues = sessionQueues.get(sessionId);
                if (queues != null) {
                    queues.remove(queueName);
                    if (queues.isEmpty()) {
                        sessionQueues.remove(sessionId);
                    }
                }

                // Unbind and delete queue
                amqpAdmin.removeBinding(BindingBuilder.bind(new Queue(queueName)).to(new DirectExchange(exchange)).with(""));
                amqpAdmin.deleteQueue(queueName);
                logger.debug("Removed RabbitMQ queue and binding: queue={}, exchange={}", queueName, exchange);
            }
        } catch (Exception e) {
            logger.error("Error syncing subscription to RabbitMQ: destination={}, sessionId={}", destination, sessionId, e);
        }
    }

    /**
     * Ensures exchange exists in RabbitMQ.
     * Uses DirectExchange: one exchange per destination.
     * Each destination (e.g., "/topic/public", "/topic/group.1") gets its own exchange.
     */
    private void ensureExchangeExists(String exchange) {
        try {
            DirectExchange directExchange = new DirectExchange(exchange, true, false);
            amqpAdmin.declareExchange(directExchange);
        } catch (Exception e) {
            logger.error("Error declaring exchange: {}", exchange, e);
        }
    }

    /**
     * Converts STOMP destination to RabbitMQ exchange name.
     * Each destination gets its own DirectExchange.
     * 
     * Example: "/topic/public" -> "topic.public"
     * Example: "/topic/group.1" -> "topic.group.1"
     * 
     * With DirectExchange, we don't need routing keys - each exchange is dedicated to one destination.
     */
    private String convertDestinationToExchange(String destination) {
        if (destination == null) {
            return "topic.default";
        }
        // Remove leading slash and replace remaining slashes with dots
        // This creates a unique exchange name for each destination
        return destination.replaceFirst("^/", "").replace("/", ".");
    }

    /**
     * Creates unique queue name for subscription
     */
    private String createQueueName(String instanceId, String sessionId, String destination) {
        // Sanitize destination for queue name
        String sanitized = destination.replace("/", ".").replaceFirst("^\\.", "");
        String queueName = "ws." + instanceId + "." + sessionId + "." + sanitized;
        logger.debug("[createQueueName] Creating queue name: queueName={}", queueName);
        return queueName;
    }
}

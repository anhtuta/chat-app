package com.hello.chatapp.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.*;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import com.hello.chatapp.listener.DynamicRabbitMQListener;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Custom RabbitMQ broker handler that syncs subscriptions to RabbitMQ
 * and handles cross-instance message distribution.
 * This works alongside Spring's SimpleBroker for local WebSocket connections.
 */
@Component
public class CustomRabbitMQBrokerHandler {

    private static final Logger logger = LoggerFactory.getLogger(CustomRabbitMQBrokerHandler.class);

    // Track local subscriptions: destination -> Set<sessionId>
    // This is in addition to SimpleBroker's internal tracking
    // (In-memory broker uses another ConcurrentHashMap for local WebSocket connections)
    private final ConcurrentHashMap<String, Set<String>> localSubscriptions = new ConcurrentHashMap<>();

    // Track queues created by this instance: destination -> queueName
    // One queue per instance per destination (shared by all sessions)
    private final ConcurrentHashMap<String, String> destinationQueues = new ConcurrentHashMap<>();

    // Track reference count for each destination: destination -> count
    // Used to know when to delete the queue (when count reaches 0)
    private final ConcurrentHashMap<String, Integer> destinationSubscriptionCount = new ConcurrentHashMap<>();

    private final RabbitTemplate rabbitTemplate;

    private final AmqpAdmin amqpAdmin;

    private DynamicRabbitMQListener dynamicListener;

    @Value("${spring.application.instance-id:${random.uuid}}")
    private String instanceId;

    public CustomRabbitMQBrokerHandler(RabbitTemplate rabbitTemplate, AmqpAdmin amqpAdmin) {
        this.rabbitTemplate = rabbitTemplate;
        this.amqpAdmin = amqpAdmin;
    }

    /**
     * Setter injection for DynamicRabbitMQListener to break circular dependency.
     * This is called after all beans are created, avoiding the circular dependency issue.
     */
    @Autowired
    @Lazy
    public void setDynamicListener(DynamicRabbitMQListener dynamicListener) {
        this.dynamicListener = dynamicListener;
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
     * Uses FanoutExchange: one exchange per destination, broadcasts to all bound queues.
     */
    public void publishToRabbitMQ(String destination, Object payload) {
        try {
            String exchange = convertDestinationToExchange(destination);

            logger.debug("Publishing to RabbitMQ: exchange={}, instanceId={}", exchange, instanceId);

            // Ensure exchange exists (FanoutExchange, one per destination)
            ensureExchangeExists(exchange);

            // Convert payload to message and add instance ID header
            Message message = rabbitTemplate.getMessageConverter().toMessage(payload, new MessageProperties());
            message.getMessageProperties().setHeader("source-instance-id", instanceId);

            // Publish to FanoutExchange (routing key is ignored for FanoutExchange)
            // FanoutExchange broadcasts messages to all bound queues
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
     * Cleans up all queues created by this instance (called on shutdown)
     */
    private void cleanupAllQueues() {
        if (!destinationQueues.isEmpty()) {
            logger.info("Cleaning up {} queues for instance: {}", destinationQueues.size(), instanceId);
            for (String queueName : destinationQueues.values()) {
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
            destinationQueues.clear();
            destinationSubscriptionCount.clear();
        }
    }

    /**
     * Syncs subscription to RabbitMQ by creating/removing queue bindings.
     * Uses FanoutExchange: one exchange per destination, one queue per instance per destination.
     * 
     * Queue is created on first subscription and deleted when last subscription is removed.
     */
    private void syncSubscriptionToRabbitMQ(String destination, String sessionId, boolean subscribe) {
        try {
            String exchange = convertDestinationToExchange(destination);

            // Ensure exchange exists (FanoutExchange, one per destination)
            ensureExchangeExists(exchange);

            if (subscribe) {
                // Increment subscription count for this destination
                int count = destinationSubscriptionCount.compute(destination, (k, v) -> (v == null) ? 1 : v + 1);

                // Create queue only if this is the first subscription to this destination
                if (count == 1) {
                    String queueName = createQueueName(instanceId, destination);

                    // Create queue and bind to FanoutExchange (no routing key needed)
                    Queue queue = QueueBuilder.durable(queueName).build();
                    amqpAdmin.declareQueue(queue);
                    amqpAdmin.declareBinding(BindingBuilder.bind(queue).to(new FanoutExchange(exchange)));
                    logger.debug("Created RabbitMQ queue and binding: queue={}, exchange={}", queueName, exchange);

                    // Track this queue
                    destinationQueues.put(destination, queueName);

                    // Start listening to this queue dynamically
                    if (dynamicListener != null) {
                        dynamicListener.startListening(queueName, destination);
                    }
                } else {
                    logger.debug("Queue already exists for destination: {}, subscription count: {}", destination, count);
                }
            } else {
                // Decrement subscription count for this destination
                int count = destinationSubscriptionCount.compute(destination, (k, v) -> (v == null || v <= 1) ? 0 : v - 1);

                // Delete queue only if this was the last subscription to this destination
                if (count == 0) {
                    String queueName = destinationQueues.remove(destination);
                    destinationSubscriptionCount.remove(destination);

                    if (queueName != null) {
                        // Stop listening to this queue
                        if (dynamicListener != null) {
                            dynamicListener.stopListening(queueName);
                        }

                        // Unbind and delete queue (bindings are automatically removed when queue is deleted)
                        amqpAdmin.deleteQueue(queueName);
                        logger.debug("Removed RabbitMQ queue and binding: queue={}, exchange={}", queueName, exchange);
                    }
                } else {
                    logger.debug("Queue still in use for destination: {}, remaining subscriptions: {}", destination, count);
                }
            }
        } catch (Exception e) {
            logger.error("Error syncing subscription to RabbitMQ: destination={}, sessionId={}", destination, sessionId, e);
        }
    }

    /**
     * Ensures exchange exists in RabbitMQ.
     * Uses FanoutExchange: one exchange per destination.
     * Each destination (e.g., "/topic/public", "/topic/group.1") gets its own exchange.
     * FanoutExchange broadcasts messages to all bound queues (no routing key needed).
     */
    private void ensureExchangeExists(String exchange) {
        try {
            FanoutExchange fanoutExchange = new FanoutExchange(exchange, true, false);
            amqpAdmin.declareExchange(fanoutExchange);
        } catch (Exception e) {
            logger.warn("Error declaring exchange {} (may already exist with different properties): {}",
                    exchange, e.getMessage());
        }
    }

    /**
     * Converts STOMP destination to RabbitMQ exchange name.
     * Each destination gets its own FanoutExchange.
     * 
     * Example: "/topic/public" -> "topic.public"
     * Example: "/topic/group.1" -> "topic.group.1"
     * 
     * With FanoutExchange, messages are broadcast to all bound queues (no routing key needed).
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
     * Creates queue name for a destination.
     * One queue per instance per destination (shared by all sessions on that instance).
     * 
     * Example: instanceId="instance-1", destination="/topic/group.1"
     * -> "ws.instance-1.topic.group.1"
     */
    private String createQueueName(String instanceId, String destination) {
        // Sanitize destination for queue name
        String sanitized = destination.replace("/", ".").replaceFirst("^\\.", "");
        String queueName = "ws." + instanceId + "." + sanitized;
        logger.debug("[createQueueName] Creating queue name: queueName={}", queueName);
        return queueName;
    }
}

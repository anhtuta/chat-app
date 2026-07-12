package com.hello.chatapp.config;

import com.hello.chatapp.listener.DynamicRabbitMQListener;
import com.hello.chatapp.service.GroupUpdatesSubscriptionRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Custom RabbitMQ broker handler that syncs local STOMP subscriptions to RabbitMQ
 * and handles cross-instance message distribution.
 *
 * The topology uses fixed TopicExchanges and one inbound queue per application
 * instance. Local subscriptions add/remove bindings on the instance queue.
 */
@Component
public class CustomRabbitMQBrokerHandler {

    private static final Logger logger = LoggerFactory.getLogger(CustomRabbitMQBrokerHandler.class);

    private static final String PUBLIC_EXCHANGE = "chat.public";
    private static final String GROUPS_EXCHANGE = "chat.groups";
    private static final String USER_UPDATES_EXCHANGE = "chat.user-updates";
    private static final String DESTINATION_HEADER = "stomp-destination";

    private final ConcurrentHashMap<String, Integer> destinationSubscriptionCount = new ConcurrentHashMap<>();

    /**
     * Key: sessionId
     * Value: Map of subscriptionId -> destination.
     * 
     * In the STOMP protocol specification, an UNSUBSCRIBE frame does not contain a destination header,
     * it only transmits a unique subscriptionId. How to fix:
     * We captured the destination during the SUBSCRIBE phase, and retrieve it during the UNSUBSCRIBE phase.
     * Ref: Google AI
     */
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, String>> sessionSubscriptions = new ConcurrentHashMap<>();

    private final RabbitTemplate rabbitTemplate;
    private final AmqpAdmin amqpAdmin;
    private final DynamicRabbitMQListener dynamicListener;
    private final GroupUpdatesSubscriptionRegistry groupUpdatesSubscriptionRegistry;

    @Value("${spring.application.instance-id:${random.uuid}}")
    private String instanceId;

    private String instanceQueueName;

    public CustomRabbitMQBrokerHandler(RabbitTemplate rabbitTemplate, AmqpAdmin amqpAdmin,
            DynamicRabbitMQListener dynamicListener,
            GroupUpdatesSubscriptionRegistry groupUpdatesSubscriptionRegistry) {
        this.rabbitTemplate = rabbitTemplate;
        this.amqpAdmin = amqpAdmin;
        this.dynamicListener = dynamicListener;
        this.groupUpdatesSubscriptionRegistry = groupUpdatesSubscriptionRegistry;
    }

    public static String getDestinationHeader() {
        return DESTINATION_HEADER;
    }

    /**
     * Extracts the username from the group updates destination.
     * Example: "/topic/user.john.group-updates" -> "john"
     */
    public static Optional<String> extractGroupUpdatesUsername(String destination) {
        if (destination != null && destination.startsWith("/topic/user.") && destination.endsWith(".group-updates")) {
            return Optional.of(destination.substring(
                    "/topic/user.".length(),
                    destination.length() - ".group-updates".length()));
        }
        return Optional.empty();
    }

    public boolean hasLocalSubscribers(String destination) {
        return destinationSubscriptionCount.getOrDefault(destination, 0) > 0;
    }

    @PostConstruct
    public void init() {
        instanceQueueName = createInstanceQueueName(instanceId);
        declareFixedTopology();

        // Drop stale bindings/messages left by an unclean shutdown for this instance id.
        amqpAdmin.deleteQueue(instanceQueueName);
        Queue queue = QueueBuilder.durable(instanceQueueName).build();
        amqpAdmin.declareQueue(queue);

        if (dynamicListener != null) {
            dynamicListener.startListening(instanceQueueName, null);
        }

        logger.info("CustomRabbitMQBrokerHandler initialized for instance: {}, queue: {}", instanceId,
                instanceQueueName);
    }

    @PreDestroy
    public void cleanup() {
        logger.info("Cleaning up RabbitMQ subscriptions for instance: {}", instanceId);
        cleanupInstanceQueue();
    }

    public void handleSubscribe(String sessionId, String subscriptionId, String destination) {
        logger.debug("Handling subscribe: sessionId={}, subscriptionId={}, destination={}, instanceId={}",
                sessionId, subscriptionId, destination, instanceId);

        // Store destination for each subscriptionId, so we can retrieve it during the UNSUBSCRIBE phase.
        if (sessionId != null && subscriptionId != null) {
            sessionSubscriptions
                    .computeIfAbsent(sessionId, key -> new ConcurrentHashMap<>())
                    .put(subscriptionId, destination);
        }
        logger.debug("sessionSubscriptions: {}", sessionSubscriptions);

        syncSubscriptionToRabbitMQ(destination, true);
    }

    public void handleUnsubscribe(String sessionId, String subscriptionId) {
        logger.debug("Handling unsubscribe: sessionId={}, subscriptionId={}, instanceId={}",
                sessionId, subscriptionId, instanceId);

        // Retrieve destination for the subscriptionId.
        String destination = removeTrackedDestination(sessionId, subscriptionId);
        if (destination == null) {
            logger.debug("No tracked destination found for unsubscribe: sessionId={}, subscriptionId={}. Do nothing.",
                    sessionId, subscriptionId);
            return;
        } else {
            logger.debug("Tracked destination found for unsubscribe: sessionId={}, subscriptionId={}, destination={}",
                    sessionId, subscriptionId, destination);
            syncSubscriptionToRabbitMQ(destination, false);
        }
    }

    public void handleDisconnect(String sessionId) {
        if (sessionId == null) {
            return;
        }

        Map<String, String> subscriptions = sessionSubscriptions.remove(sessionId);
        if (subscriptions == null || subscriptions.isEmpty()) {
            return;
        }

        logger.debug("Cleaning up {} RabbitMQ subscriptions for disconnected session: {}",
                subscriptions.size(), sessionId);
        for (String destination : subscriptions.values()) {
            syncSubscriptionToRabbitMQ(destination, false);
        }
    }

    /**
     * Publishes message to RabbitMQ for cross-instance distribution.
     * Local delivery is still handled by Spring's SimpleBroker.
     */
    public void publishToRabbitMQ(String destination, Object payload) {
        try {
            DestinationRoute route = routeForDestination(destination);
            logger.debug("Publishing to RabbitMQ: from instance={} to exchange={}, routingKey={}",
                    instanceId, route.exchange(), route.routingKey());

            Message message = rabbitTemplate.getMessageConverter().toMessage(Objects.requireNonNull(payload),
                    new MessageProperties());
            message.getMessageProperties().setHeader("source-instance-id", instanceId);
            // Listener will use this header to forward the message to only users that subscribed to this destination.
            message.getMessageProperties().setHeader(DESTINATION_HEADER, destination);

            rabbitTemplate.send(route.exchange(), route.routingKey(), message);
        } catch (Exception e) {
            logger.error("Error publishing to RabbitMQ for destination: {}", destination, e);
        }
    }

    private String removeTrackedDestination(String sessionId, String subscriptionId) {
        if (sessionId == null || subscriptionId == null) {
            return null;
        }

        Map<String, String> subscriptions = sessionSubscriptions.get(sessionId);
        if (subscriptions == null) {
            return null;
        }

        String destination = subscriptions.remove(subscriptionId);
        if (subscriptions.isEmpty()) {
            sessionSubscriptions.remove(sessionId);
        }
        return destination;
    }

    private void cleanupInstanceQueue() {
        try {
            if (dynamicListener != null && instanceQueueName != null) {
                dynamicListener.stopListening(instanceQueueName);
            }
            if (instanceQueueName != null) {
                amqpAdmin.deleteQueue(instanceQueueName);
                logger.info("Deleted instance queue: {}", instanceQueueName);
            }
        } catch (Exception e) {
            logger.warn("Error deleting instance queue {}: {}", instanceQueueName, e.getMessage());
        } finally {
            destinationSubscriptionCount.clear();
            sessionSubscriptions.clear();
        }
    }

    /**
     * Sync the subscription to RabbitMQ.
     * <p>
     * In the first time of subscribing to destination (on each instance), it will:
     * 1. create a binding between the instance queue and the exchange.
     * 2. track the group-updates subscription for the user (in Redis).
     * 
     * In the last time of unsubscribing to destination (on each instance), it will:
     * 1. remove the binding between the instance queue and the exchange.
     * 2. remove the group-updates subscription for the user (in Redis). If all instances have unsubscribed,
     * the user will be removed from Redis.
     * </p>
     */
    private synchronized void syncSubscriptionToRabbitMQ(String destination, boolean subscribe) {
        try {
            DestinationRoute route = routeForDestination(destination);
            Queue queue = QueueBuilder.durable(instanceQueueName).build();
            TopicExchange exchange = topicExchange(route.exchange());

            if (subscribe) {
                int count = destinationSubscriptionCount.compute(destination, (key, value) -> value == null ? 1 : value + 1);
                if (count == 1) {
                    amqpAdmin.declareBinding(BindingBuilder.bind(queue).to(exchange).with(route.routingKey()));
                    logger.debug("Created RabbitMQ binding: queue={}, exchange={}, routingKey={}, destination={}",
                            instanceQueueName, route.exchange(), route.routingKey(), destination);
                    trackGroupUpdatesClusterSubscription(destination, true);
                }
            } else {
                int count = destinationSubscriptionCount.compute(destination,
                        (key, value) -> value == null || value <= 1 ? 0 : value - 1);
                if (count == 0) {
                    destinationSubscriptionCount.remove(destination);
                    Binding binding = BindingBuilder.bind(queue).to(exchange).with(route.routingKey());
                    amqpAdmin.removeBinding(binding);
                    logger.debug("Removed RabbitMQ binding: queue={}, exchange={}, routingKey={}, destination={}",
                            instanceQueueName, route.exchange(), route.routingKey(), destination);
                    trackGroupUpdatesClusterSubscription(destination, false);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Error syncing subscription to RabbitMQ: destination=" + destination, e);
        }
        logger.debug("destinationSubscriptionCount: {}", destinationSubscriptionCount);
    }

    private void declareFixedTopology() {
        amqpAdmin.declareExchange(topicExchange(PUBLIC_EXCHANGE));
        amqpAdmin.declareExchange(topicExchange(GROUPS_EXCHANGE));
        amqpAdmin.declareExchange(topicExchange(USER_UPDATES_EXCHANGE));
    }

    private DestinationRoute routeForDestination(String destination) {
        // Public group channel
        if ("/topic/public".equals(destination)) {
            return new DestinationRoute(PUBLIC_EXCHANGE, "public");
        }

        // Private group channel
        if (destination != null && destination.startsWith("/topic/group.")) {
            String groupId = destination.substring("/topic/group.".length());
            return new DestinationRoute(GROUPS_EXCHANGE, "group." + groupId);
        }

        // Group-updates channel
        Optional<String> groupUpdatesUsername = extractGroupUpdatesUsername(destination);
        if (groupUpdatesUsername.isPresent()) {
            String username = groupUpdatesUsername.get();
            return new DestinationRoute(USER_UPDATES_EXCHANGE, "user." + username + ".group-updates");
        }
        throw new IllegalArgumentException("Unsupported RabbitMQ-backed STOMP destination: " + destination);
    }

    private TopicExchange topicExchange(String exchangeName) {
        return new TopicExchange(exchangeName, true, false);
    }

    private String createInstanceQueueName(String instanceId) {
        String queueName = "ws." + instanceId + ".inbound";
        logger.info("[createInstanceQueueName] Creating queue name: queueName={}", queueName);
        return queueName;
    }

    /**
     * Track the cluster subscription for group-updates destination.
     * Basically: it stores users in Redis that have a group-updates subscription on any instance in the cluster.
     */
    private void trackGroupUpdatesClusterSubscription(String destination, boolean subscribed) {
        extractGroupUpdatesUsername(destination).ifPresent(username -> {
            if (subscribed) {
                groupUpdatesSubscriptionRegistry.trackLocalSubscriptionOpened(username);
            } else {
                groupUpdatesSubscriptionRegistry.trackLocalSubscriptionClosed(username);
            }
        });
    }

    /**
     * A record to represent a destination route in RabbitMQ.
     * 
     * @param exchange The name of the exchange to publish to.
     * @param routingKey The routing key to use for the message.
     */
    private record DestinationRoute(String exchange, String routingKey) {
    }
}

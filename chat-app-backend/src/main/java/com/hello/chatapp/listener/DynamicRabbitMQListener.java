package com.hello.chatapp.listener;

import com.hello.chatapp.config.CustomRabbitMQBrokerHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageListener;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dynamic message listener that consumes from the per-instance inbound queue.
 * 
 * This class is responsible for:
 * - Creating and managing RabbitMQ listeners (SimpleMessageListenerContainer objects)
 * - Processing messages (checking instance ID, deserializing, forwarding)
 */
@Component
public class DynamicRabbitMQListener {

    private static final Logger logger = LoggerFactory.getLogger(DynamicRabbitMQListener.class);

    private final ConnectionFactory connectionFactory;
    private final MessageConverter messageConverter;

    private SimpMessagingTemplate messagingTemplate;

    @Value("${spring.application.instance-id:${random.uuid}}")
    private String instanceId;

    // Track active listeners: queueName -> MessageListenerContainer
    private final Map<String, SimpleMessageListenerContainer> activeListeners = new ConcurrentHashMap<>();

    public DynamicRabbitMQListener(ConnectionFactory connectionFactory, MessageConverter messageConverter) {
        this.connectionFactory = connectionFactory;
        this.messageConverter = messageConverter;
    }

    @Autowired
    @Lazy
    public void setMessagingTemplate(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    /**
     * Starts listening to a queue.
     * Called once when the instance queue is created.
     */
    public void startListening(String queueName) {
        startListening(queueName, null);
    }

    /**
     * Compatibility overload for older call sites. The destination is carried per message
     * in a header, not fixed per queue.
     */
    public void startListening(String queueName, String ignoredDestination) {
        // Don't create duplicate listeners
        if (activeListeners.containsKey(queueName)) {
            logger.debug("Listener already exists for queue: {}", queueName);
            return;
        }

        logger.debug("Starting listener for queue: {}, instanceId: {}", queueName, instanceId);

        // Create a new listener/consumer for this queue
        SimpleMessageListenerContainer container = new SimpleMessageListenerContainer();
        container.setConnectionFactory(Objects.requireNonNull(connectionFactory));
        container.setQueueNames(queueName);
        container.setMessageListener(new MessageListener() {
            @Override
            public void onMessage(Message message) {
                try {
                    // Check if message came from this instance (skip to avoid duplicate)
                    String sourceInstanceId = (String) message.getMessageProperties().getHeaders().get("source-instance-id");
                    if (instanceId.equals(sourceInstanceId)) {
                        logger.debug("Skipping message from same instance: queue={}, instanceId={}", queueName, instanceId);
                        return;
                    }

                    // Deserialize payload using the configured MessageConverter
                    Object payload = messageConverter.fromMessage(message);
                    String destination = (String) message.getMessageProperties().getHeaders()
                            .get(CustomRabbitMQBrokerHandler.getDestinationHeader());
                    if (destination == null) {
                        logger.warn("Skipping RabbitMQ message without STOMP destination header: queue={}", queueName);
                        return;
                    }

                    logger.info("Received message from queue: {}, destination: {}, instanceId: {}",
                            queueName, destination, instanceId);

                    // Forward to local subscribers
                    forwardToLocalSubscribers(destination, payload);
                } catch (Exception e) {
                    logger.error("Error processing message from queue: {}", queueName, e);
                }
            }
        });

        container.start();
        activeListeners.put(queueName, container);

        logger.debug("Started listener for queue: {}", queueName);
    }

    /**
     * Stops listening to a queue.
     * Called when a subscription is removed.
     */
    public void stopListening(String queueName) {
        SimpleMessageListenerContainer container = activeListeners.remove(queueName);
        if (container != null) {
            logger.debug("Stopping listener for queue: {}, instanceId: {}", queueName, instanceId);
            // Stop the consumer before deleting the queue, otherwise the container may throw errors
            // trying to consume from a non-existent queue
            container.stop();
            logger.debug("Stopped listener for queue: {}", queueName);
        }
    }

    /**
     * Forwards message to local WebSocket subscribers.
     * Without this, messages from other instances would be received from RabbitMQ
     * but never delivered to local WebSocket clients.
     * Note: SimpMessagingTemplate.convertAndSend() is safe to call even when there are no subscribers.
     * It will simply publish to the destination, and if no one is subscribed, nothing happens.
     */
    private void forwardToLocalSubscribers(String destination, Object payload) {
        if (messagingTemplate != null) {
            logger.debug("Forwarding to local subscribers: destination={}, instanceId={}", destination, instanceId);
            messagingTemplate.convertAndSend(Objects.requireNonNull(destination), Objects.requireNonNull(payload));
        } else {
            logger.warn("SimpMessagingTemplate is not set, cannot forward message to local subscribers");
        }
    }
}


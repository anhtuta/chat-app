package com.hello.chatapp.listener;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageListener;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dynamic message listener that consumes from per-subscription queues.
 * 
 * With DirectExchange approach:
 * - Each subscription creates its own queue (e.g., "ws.instance-1.session-123.topic.group.1")
 * - This listener dynamically subscribes to those queues as they're created
 * - Messages are processed and forwarded to local WebSocket subscribers
 * 
 * This class is responsible for:
 * - Creating and managing SimpleMessageListenerContainer instances
 * - Processing messages (checking instance ID, deserializing, forwarding)
 */
@Component
public class DynamicRabbitMQListener {

    private static final Logger logger = LoggerFactory.getLogger(DynamicRabbitMQListener.class);

    private final ConnectionFactory connectionFactory;
    private final MessageConverter messageConverter;
    private final SimpMessagingTemplate messagingTemplate;

    @Value("${spring.application.instance-id:${random.uuid}}")
    private String instanceId;

    // Track active listeners: queueName -> MessageListenerContainer
    private final Map<String, SimpleMessageListenerContainer> activeListeners = new ConcurrentHashMap<>();

    public DynamicRabbitMQListener(ConnectionFactory connectionFactory,
            MessageConverter messageConverter, SimpMessagingTemplate messagingTemplate) {
        this.connectionFactory = connectionFactory;
        this.messageConverter = messageConverter;
        this.messagingTemplate = messagingTemplate;
    }

    /**
     * Starts listening to a queue for a specific destination.
     * Called when a subscription is created.
     */
    public void startListening(String queueName, String destination) {
        // Don't create duplicate listeners
        if (activeListeners.containsKey(queueName)) {
            logger.debug("Listener already exists for queue: {}", queueName);
            return;
        }

        logger.debug("Starting listener for queue: {}, destination: {}, instanceId: {}", queueName, destination, instanceId);

        // Create a new listener/consumer for this queue
        SimpleMessageListenerContainer container = new SimpleMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
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
     * 
     * This is REQUIRED for cross-instance messaging:
     * - When a message comes from RabbitMQ (from another instance), DynamicRabbitMQListener calls this method
     * - This method uses SimpMessagingTemplate to deliver to SimpleBroker, which then delivers to local WebSocket subscribers
     * 
     * Note: SimpMessagingTemplate.convertAndSend() is safe to call even when there are no subscribers.
     * It will simply publish to the destination, and if no one is subscribed, nothing happens.
     */
    private void forwardToLocalSubscribers(String destination, Object payload) {
        if (messagingTemplate != null) {
            logger.debug("Forwarding to local subscribers: destination={}, instanceId={}", destination, instanceId);
            messagingTemplate.convertAndSend(destination, payload);
        }
    }
}


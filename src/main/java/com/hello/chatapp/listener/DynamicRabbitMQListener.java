package com.hello.chatapp.listener;

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
import com.hello.chatapp.config.CustomRabbitMQBrokerHandler;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dynamic message listener that consumes from the single instance queue.
 * 
 * This class is responsible for:
 * - Creating and managing RabbitMQ listener for the single instance queue
 * - Processing messages (checking instance ID, filtering by destination, deserializing, forwarding)
 */
@Component
public class DynamicRabbitMQListener {

    private static final Logger logger = LoggerFactory.getLogger(DynamicRabbitMQListener.class);

    private final ConnectionFactory connectionFactory;
    private final MessageConverter messageConverter;

    private SimpMessagingTemplate messagingTemplate;
    private CustomRabbitMQBrokerHandler brokerHandler;

    @Value("${spring.application.instance-id:${random.uuid}}")
    private String instanceId;

    // Track active listener: queueName -> MessageListenerContainer
    // With single queue architecture, there should only be one listener
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

    @Autowired
    @Lazy
    public void setBrokerHandler(CustomRabbitMQBrokerHandler brokerHandler) {
        this.brokerHandler = brokerHandler;
    }

    /**
     * Starts listening to the single instance queue.
     * With single queue architecture, this is called once during initialization.
     * The listener filters messages by destination before forwarding.
     * 
     * @param queueName the queue name to listen to
     * @param destination ignored (kept for backward compatibility), destination is extracted from message headers
     */
    public void startListening(String queueName, @SuppressWarnings("unused") String destination) {
        // Don't create duplicate listeners
        if (activeListeners.containsKey(queueName)) {
            logger.debug("Listener already exists for queue: {}", queueName);
            return;
        }

        logger.debug("Starting listener for single queue: {}, instanceId: {}", queueName, instanceId);

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

                    // Get destination from message headers (set by publisher)
                    String destination = (String) message.getMessageProperties().getHeaders().get("destination");
                    if (destination == null) {
                        logger.warn("Received message without destination header, skipping");
                        return;
                    }

                    // Check if this instance is subscribed to this destination
                    if (brokerHandler != null && !brokerHandler.isSubscribedToDestination(destination)) {
                        logger.debug("Skipping message for destination we're not subscribed to: destination={}, instanceId={}",
                                destination, instanceId);
                        return;
                    }

                    // Deserialize payload using the configured MessageConverter
                    Object payload = messageConverter.fromMessage(message);

                    logger.debug("Received message from queue: {}, destination: {}, instanceId: {}",
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

        logger.info("Started listener for single queue: {}", queueName);
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
            messagingTemplate.convertAndSend(destination, payload);
        } else {
            logger.warn("SimpMessagingTemplate is not set, cannot forward message to local subscribers");
        }
    }
}


package com.hello.chatapp.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageListener;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.hello.chatapp.processor.RabbitMQMessageProcessor;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dynamic message listener that consumes from per-subscription queues.
 * 
 * With DirectExchange approach:
 * - Each subscription creates its own queue (e.g., "ws.instance-1.session-123.topic.group.1")
 * - This listener dynamically subscribes to those queues as they're created
 * - Messages are processed by RabbitMQMessageProcessor (separated to avoid circular dependency)
 * - Then in RabbitMQMessageProcessor, messages are forwarded to local WebSocket subscribers
 * 
 * This class is responsible only for:
 * - Creating and managing SimpleMessageListenerContainer instances
 * - Delegating message processing to RabbitMQMessageProcessor
 */
@Component
public class DynamicRabbitMQListener {

    private static final Logger logger = LoggerFactory.getLogger(DynamicRabbitMQListener.class);

    private final ConnectionFactory connectionFactory;
    private final RabbitMQMessageProcessor messageProcessor;

    @Value("${spring.application.instance-id:${random.uuid}}")
    private String instanceId;

    // Track active listeners: queueName -> MessageListenerContainer
    private final Map<String, SimpleMessageListenerContainer> activeListeners = new ConcurrentHashMap<>();

    public DynamicRabbitMQListener(ConnectionFactory connectionFactory,
            RabbitMQMessageProcessor messageProcessor) {
        this.connectionFactory = connectionFactory;
        this.messageProcessor = messageProcessor;
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

        logger.debug("Starting listener for queue: {}, destination: {}, instanceId: {}",
                queueName, destination, instanceId);

        // Create a new listener/consumer for this queue
        SimpleMessageListenerContainer container = new SimpleMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.setQueueNames(queueName);
        container.setMessageListener(new MessageListener() {
            @Override
            public void onMessage(Message message) {
                // Delegate message processing to RabbitMQMessageProcessor
                messageProcessor.processMessage(message, destination, queueName);
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
}


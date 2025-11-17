package com.hello.chatapp.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration for RabbitMQ integration.
 * Sets up exchanges, queues, and message converters for cross-instance messaging.
 * 
 * This configuration enables the hybrid approach:
 * - SimpleBroker handles local WebSocket connections (in-memory)
 * - RabbitMQ handles cross-instance message distribution
 */
@Configuration
public class RabbitMQConfig {

    @Value("${spring.application.instance-id:${random.uuid}}")
    private String instanceId;

    /**
     * AmqpAdmin bean for managing RabbitMQ resources (queues, exchanges, bindings).
     * 
     * Used by: CustomRabbitMQBrokerHandler
     * - Declares exchanges when publishing messages
     * - Creates/deletes queues when clients subscribe/unsubscribe
     * - Creates/removes bindings between queues and exchanges
     * 
     * Spring Framework: Automatically injected into beans that need it
     */
    @Bean
    public AmqpAdmin amqpAdmin(ConnectionFactory connectionFactory) {
        return new RabbitAdmin(connectionFactory);
    }

    /**
     * MessageConverter bean for serializing/deserializing messages to/from JSON.
     * 
     * Used by:
     * - RabbitTemplate: Converts Java objects to JSON when publishing
     * - RabbitListenerContainerFactory: Converts JSON to Java objects when consuming
     * 
     * Spring Framework: Automatically used by RabbitTemplate and @RabbitListener infrastructure
     */
    @Bean
    public MessageConverter messageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    /**
     * RabbitTemplate bean for publishing messages to RabbitMQ.
     * 
     * Used by: CustomRabbitMQBrokerHandler.publishToRabbitMQ()
     * - Publishes messages to RabbitMQ exchanges for cross-instance distribution
     * 
     * Spring Framework: Automatically injected into beans that need it
     */
    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory, MessageConverter messageConverter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(messageConverter);
        return template;
    }

    /**
     * RabbitListenerContainerFactory bean for configuring @RabbitListener methods.
     * 
     * Used by: Spring Framework's @RabbitListener infrastructure
     * - Automatically used when processing @RabbitListener annotations
     * - Configures how messages are consumed from queues
     * - Sets up message conversion, error handling, etc.
     * 
     * Referenced in: RabbitMQMessageListener.handlePublicTopicMessage() and handleGroupTopicMessage()
     */
    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(ConnectionFactory connectionFactory) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(messageConverter());
        return factory;
    }

    /**
     * Queue for public topic messages.
     * Each instance has its own queue (e.g., "ws.instance-1.public", "ws.instance-2.public").
     * 
     * Used by: RabbitMQMessageListener.handlePublicTopicMessage()
     * - Referenced via @RabbitListener(queues = "#{@publicTopicQueue}")
     * - Receives messages published to topic.public exchange
     * - Bound to topic.public exchange with routing key "#" (matches all messages)
     * 
     * Spring Framework: Automatically declared in RabbitMQ when bean is created
     */
    @Bean
    public Queue publicTopicQueue() {
        String queueName = "ws." + instanceId + ".public";
        return QueueBuilder.durable(queueName).build();
    }

    /**
     * Exchange for public topic messages.
     * 
     * Note: With DirectExchange approach, this is created for backward compatibility.
     * However, CustomRabbitMQBrokerHandler will create exchanges dynamically per destination.
     * 
     * Used by:
     * - CustomRabbitMQBrokerHandler: Publishes messages to "topic.public" exchange
     * - publicTopicBinding: Binds publicTopicQueue to this exchange
     * 
     * Spring Framework: Automatically declared in RabbitMQ when bean is created
     * - durable=true: Exchange survives RabbitMQ server restarts
     * - autoDelete=false: Exchange is not deleted when no queues are bound
     */
    @Bean
    public DirectExchange publicTopicExchange() {
        return new DirectExchange("topic.public", true, false);
    }

    /**
     * Binding that connects publicTopicQueue to publicTopicExchange.
     * 
     * Used by: Spring Framework automatically
     * - When the bean is created, Spring automatically declares this binding in RabbitMQ
     * - With DirectExchange, empty routing key ("") means the queue receives all messages
     *   published to topic.public exchange with empty routing key
     * 
     * This enables: Messages published to topic.public are delivered to all instances' publicTopicQueue
     */
    @Bean
    public Binding publicTopicBinding() {
        return BindingBuilder.bind(publicTopicQueue())
                .to(publicTopicExchange())
                .with(""); // Empty routing key for DirectExchange
    }
}

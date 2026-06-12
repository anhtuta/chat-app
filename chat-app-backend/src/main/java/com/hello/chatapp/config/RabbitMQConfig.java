package com.hello.chatapp.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.NonNull;

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

    /**
     * AmqpAdmin bean for managing RabbitMQ resources (queues, exchanges, bindings).
     * 
     * Used by: CustomRabbitMQBrokerHandler
     * - Declares fixed topic exchanges at startup
     * - Creates/deletes the per-instance inbound queue
     * - Creates/removes destination bindings on the instance queue
     * 
     * Spring Framework: Automatically injected into beans that need it
     */
    @Bean
    public AmqpAdmin amqpAdmin(@NonNull ConnectionFactory connectionFactory) {
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
    public RabbitTemplate rabbitTemplate(@NonNull ConnectionFactory connectionFactory,
            @NonNull MessageConverter messageConverter) {
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
     * Note: Currently not used since we use DynamicRabbitMQListener with SimpleMessageListenerContainer
     * instead of @RabbitListener annotations. Kept for potential future use.
     */
    // @Bean
    // public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(ConnectionFactory connectionFactory) {
    // SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
    // factory.setConnectionFactory(connectionFactory);
    // factory.setMessageConverter(messageConverter());
    // return factory;
    // }

    /**
     * Current topology:
     * - Fixed TopicExchanges: chat.public, chat.groups, chat.user-updates
     * - One queue per backend instance: ws.{instance-id}.inbound
     * - SUBSCRIBE/UNSUBSCRIBE add and remove bindings on that instance queue
     * - DynamicRabbitMQListener consumes the instance queue and forwards messages to the
     * original STOMP destination stored in the message header
     */
}

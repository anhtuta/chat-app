package com.hello.chatapp.config;

import com.hello.chatapp.interceptor.RabbitMQSubscriptionInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.NonNull;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * Configure WebSocket with Hybrid approach:
 * - Simple Broker for local WebSocket connections (in-memory)
 * - RabbitMQ for cross-instance message distribution (via CustomRabbitMQBrokerHandler)
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Autowired
    private WebSocketSecurityChannelInterceptor securityChannelInterceptor;

    @Autowired
    private RabbitMQSubscriptionInterceptor rabbitMQSubscriptionInterceptor;

    @Override
    public void configureClientInboundChannel(@NonNull ChannelRegistration registration) {
        // Add both interceptors: security first, then RabbitMQ subscription tracking
        registration.interceptors(securityChannelInterceptor, rabbitMQSubscriptionInterceptor);
    }

    @Override
    public void configureMessageBroker(@NonNull MessageBrokerRegistry config) {
        // Use simple broker for local WebSocket connections
        // This maintains in-memory subscription registry for fast local delivery
        config.enableSimpleBroker("/topic");

        // The messages whose destination starts with "/app" should be routed to message-handling methods (check WebSocketController).
        // E.g. a message with destination /app/chat.send will be routed to a method that has @MessageMapping("/chat.send")
        config.setApplicationDestinationPrefixes("/app");
    }

    // STOMP stands for Simple Text Oriented Messaging Protocol. It is a messaging protocol that defines
    // the format and rules for data exchange. Why do we need STOMP? Well, WebSocket is just a communication protocol.
    // It doesn't define things like - How to send a message only to users who are subscribed to a particular topic,
    // or how to send a message to a particular user. We need STOMP for these functionalities.
    @Override
    public void registerStompEndpoints(@NonNull StompEndpointRegistry registry) {
        // Register the endpoint for the WebSocket connection
        registry.addEndpoint("/ws")
                // Allow all origins
                .setAllowedOriginPatterns("*")
                // Add handshake interceptor to extract username from HTTP session
                .addInterceptors(new WebSocketHandshakeInterceptor())
                // SockJS is used to enable fallback options for browsers that don't support websocket
                .withSockJS();
    }
}

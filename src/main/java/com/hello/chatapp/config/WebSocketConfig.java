package com.hello.chatapp.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.NonNull;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * Enable a simple in-memory message broker. But we can use any other full-featured message broker like RabbitMQ or ActiveMQ.
 * TODO use RabbitMQ, or Redis pub/sub in the future.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Autowired
    private WebSocketSecurityChannelInterceptor securityChannelInterceptor;

    @Override
    public void configureClientInboundChannel(@NonNull ChannelRegistration registration) {
        registration.interceptors(securityChannelInterceptor);
    }

    @Override
    public void configureMessageBroker(@NonNull MessageBrokerRegistry config) {
        // The messages whose destination starts with “/topic” should be routed to the message broker.
        // Message broker broadcasts messages to all the connected clients who are subscribed to a particular topic.
        // enableSimpleBroker --> SimpleBrokerMessageHandler is used to maintain the subscription mapping.
        config.enableSimpleBroker("/topic");
        // The messages whose destination starts with “/app” should be routed to message-handling methods (check WebSocketController).
        // E.g. a message with destination /app/chat.send will be routed to a method that has @MessageMapping("/chat.send")
        config.setApplicationDestinationPrefixes("/app");
    }

    // STOMP stands for Simple Text Oriented Messaging Protocol. It is a messaging protocol that defines
    // the format and rules for data exchange. Why do we need STOMP? Well, WebSocket is just a communication protocol.
    // It doesn’t define things like - How to send a message only to users who are subscribed to a particular topic,
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

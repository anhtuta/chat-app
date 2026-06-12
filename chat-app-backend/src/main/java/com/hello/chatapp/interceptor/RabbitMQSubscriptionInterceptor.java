package com.hello.chatapp.interceptor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.NonNull;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;
import com.hello.chatapp.config.CustomRabbitMQBrokerHandler;

/**
 * Interceptor that tracks STOMP SUBSCRIBE/UNSUBSCRIBE commands
 * and syncs them to RabbitMQ via CustomRabbitMQBrokerHandler.
 */
@Component
public class RabbitMQSubscriptionInterceptor implements ChannelInterceptor {

    private static final Logger logger = LoggerFactory.getLogger(RabbitMQSubscriptionInterceptor.class);

    private final CustomRabbitMQBrokerHandler brokerHandler;

    public RabbitMQSubscriptionInterceptor(CustomRabbitMQBrokerHandler brokerHandler) {
        this.brokerHandler = brokerHandler;
    }

    @Override
    public Message<?> preSend(@NonNull Message<?> message, @NonNull MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

        if (accessor != null) {
            StompCommand command = accessor.getCommand();
            String sessionId = accessor.getSessionId();
            String destination = accessor.getDestination();
            String subscriptionId = accessor.getSubscriptionId();

            // Skip null commands (heartbeats) and outbound MESSAGE commands
            if (command == null || StompCommand.MESSAGE.equals(command)) {
                return message;
            }

            // Handle SUBSCRIBE command
            if (StompCommand.SUBSCRIBE.equals(command) && destination != null) {
                logger.debug("Intercepting SUBSCRIBE: sessionId={}, subscriptionId={}, destination={}",
                        sessionId, subscriptionId, destination);
                brokerHandler.handleSubscribe(sessionId, subscriptionId, destination);
            }

            // Handle UNSUBSCRIBE command: the destination always = null (see brokerHandler for more details).
            else if (StompCommand.UNSUBSCRIBE.equals(command)) {
                logger.debug("Intercepting UNSUBSCRIBE: sessionId={}, subscriptionId={}", sessionId, subscriptionId);
                brokerHandler.handleUnsubscribe(sessionId, subscriptionId);
            }

            // Handle DISCONNECT command
            else if (StompCommand.DISCONNECT.equals(command)) {
                logger.debug("Intercepting DISCONNECT: sessionId={}", sessionId);
                brokerHandler.handleDisconnect(sessionId);
            }
        }

        return message;
    }
}

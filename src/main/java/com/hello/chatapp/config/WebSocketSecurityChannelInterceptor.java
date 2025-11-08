package com.hello.chatapp.config;

import org.springframework.lang.NonNull;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Channel interceptor that validates WebSocket messages come from authenticated users.
 * Checks that username exists in WebSocket session (set during handshake from HTTP session).
 */
@Component
public class WebSocketSecurityChannelInterceptor implements ChannelInterceptor {

    @Override
    public Message<?> preSend(@NonNull Message<?> message, @NonNull MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

        if (accessor != null) {
            // Skip validation for CONNECT command (handled by handshake interceptor)
            if (StompCommand.CONNECT.equals(accessor.getCommand())) {
                return message;
            }

            // For all other commands (SEND, SUBSCRIBE, etc.), validate authentication
            validateAuthentication(accessor);
        }

        return message;
    }

    /**
     * Validates that the user is authenticated (username exists in WebSocket session).
     * Throws SecurityException if not authenticated.
     */
    private void validateAuthentication(StompHeaderAccessor accessor) {
        Map<String, Object> sessionAttributes = accessor.getSessionAttributes();

        if (sessionAttributes == null) {
            throw new SecurityException("Session attributes not found. Please reconnect.");
        }

        String username = (String) sessionAttributes.get("username");

        if (username == null || username.trim().isEmpty()) {
            throw new SecurityException("User is not authenticated. Please login and reconnect.");
        }
    }
}


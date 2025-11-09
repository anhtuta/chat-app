package com.hello.chatapp.config;

import com.hello.chatapp.dto.MessageResponse;
import com.hello.chatapp.entity.Message;
import com.hello.chatapp.entity.User;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.lang.NonNull;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageSendingOperations;
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

    @Autowired
    @Lazy
    private SimpMessageSendingOperations messagingTemplate;

    @Override
    public org.springframework.messaging.Message<?> preSend(@NonNull org.springframework.messaging.Message<?> message,
            @NonNull MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

        if (accessor != null) {
            // Validate authentication for ALL commands
            User user = validateAuthentication(accessor);

            // For CONNECT command, also send join notification
            if (StompCommand.CONNECT.equals(accessor.getCommand())) {
                handleConnect(user);
            }
        }

        return message;
    }

    /**
     * Handles STOMP CONNECT command by sending a join notification.
     * User is already validated at this point.
     * Note: System messages are not saved to database, only sent to clients.
     */
    private void handleConnect(User user) {
        Message joinMessage = new Message(user, "[SYSTEM] " + user.getUsername() + " connected");
        MessageResponse response = MessageResponse.fromMessage(joinMessage);
        messagingTemplate.convertAndSend("/topic/public", response);
    }

    /**
     * Validates that the user is authenticated (user object exists in WebSocket session).
     * Returns the authenticated user if valid, throws SecurityException if not authenticated.
     */
    private User validateAuthentication(StompHeaderAccessor accessor) {
        Map<String, Object> sessionAttributes = accessor.getSessionAttributes();

        if (sessionAttributes == null) {
            throw new SecurityException("Session attributes not found. Please reconnect.");
        }

        User user = (User) sessionAttributes.get("user");

        if (user == null) {
            throw new SecurityException("User is not authenticated. Please login and reconnect.");
        }

        return user;
    }
}


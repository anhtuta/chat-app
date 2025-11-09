package com.hello.chatapp.config;

import com.hello.chatapp.entity.Message;
import com.hello.chatapp.entity.User;
import com.hello.chatapp.repository.MessageRepository;
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

    @Autowired
    private MessageRepository messageRepository;

    @Override
    public org.springframework.messaging.Message<?> preSend(@NonNull org.springframework.messaging.Message<?> message,
            @NonNull MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

        if (accessor != null) {
            // Handle CONNECT command - send join notification
            if (StompCommand.CONNECT.equals(accessor.getCommand())) {
                handleConnect(accessor);
                return message;
            }

            // For all other commands (SEND, SUBSCRIBE, etc.), validate authentication
            validateAuthentication(accessor);
        }

        return message;
    }

    /**
     * Handles STOMP CONNECT command by sending a join notification.
     * Session attributes should be available at this point.
     */
    private void handleConnect(StompHeaderAccessor accessor) {
        Map<String, Object> sessionAttributes = accessor.getSessionAttributes();
        if (sessionAttributes != null) {
            User user = (User) sessionAttributes.get("user");
            if (user != null) {
                Message joinMessage = new Message(user, "[SYSTEM] " + user.getUsername() + " connected");
                Message savedMessage = messageRepository.save(joinMessage);
                messagingTemplate.convertAndSend("/topic/public", savedMessage);
            }
        }
    }

    /**
     * Validates that the user is authenticated (user object exists in WebSocket session).
     * Throws SecurityException if not authenticated.
     */
    private void validateAuthentication(StompHeaderAccessor accessor) {
        Map<String, Object> sessionAttributes = accessor.getSessionAttributes();

        if (sessionAttributes == null) {
            throw new SecurityException("Session attributes not found. Please reconnect.");
        }

        User user = (User) sessionAttributes.get("user");

        if (user == null) {
            throw new SecurityException("User is not authenticated. Please login and reconnect.");
        }
    }
}


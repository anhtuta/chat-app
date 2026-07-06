package com.hello.chatapp.interceptor;

import com.hello.chatapp.config.CustomRabbitMQBrokerHandler;
import com.hello.chatapp.constant.GroupPermission;
import com.hello.chatapp.dto.MessageResponse;
import com.hello.chatapp.entity.Message;
import com.hello.chatapp.entity.User;
import com.hello.chatapp.exception.ForbiddenException;
import com.hello.chatapp.exception.NotFoundException;
import com.hello.chatapp.service.GroupAuthorizationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.util.Objects;

/**
 * Channel interceptor that validates WebSocket messages come from authenticated users.
 * Also validates that users can only subscribe to groups they are members of.
 */
@Component
public class WebSocketSecurityChannelInterceptor implements ChannelInterceptor {

    private static final Logger logger = LoggerFactory.getLogger(WebSocketSecurityChannelInterceptor.class);

    @Autowired
    @Lazy
    private SimpMessageSendingOperations messagingTemplate;

    @Autowired
    private CustomRabbitMQBrokerHandler rabbitMQBrokerHandler;

    @Autowired
    private GroupAuthorizationService groupAuthorizationService;

    @Override
    public org.springframework.messaging.Message<?> preSend(@NonNull org.springframework.messaging.Message<?> message,
            @NonNull MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

        if (accessor != null) {
            StompCommand command = accessor.getCommand();

            // Skip processing for:
            // 1. Null commands (heartbeat messages are often empty frames without commands)
            // 2. MESSAGE commands (outbound messages from server to clients)
            if (command == null || StompCommand.MESSAGE.equals(command)) {
                return message;
            }

            // Log meaningful STOMP commands (skip heartbeats which have null command)
            logger.debug("[preSend] message: {}, STOMP command: {}, destination: {}", new String((byte[]) message.getPayload()),
                    command, accessor.getDestination());

            // Validate authentication for inbound client commands (CONNECT, SUBSCRIBE, SEND, etc.)
            User user = validateAuthentication(accessor);

            // For CONNECT command, also send join notification
            if (StompCommand.CONNECT.equals(command)) {
                handleConnect(user);
            }

            // For SUBSCRIBE command, validate group membership if subscribing to a group topic
            if (StompCommand.SUBSCRIBE.equals(command)) {
                validateSubscription(accessor, user);
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
        MessageResponse response = Objects.requireNonNull(MessageResponse.fromMessage(joinMessage));
        messagingTemplate.convertAndSend("/topic/public", response);
        rabbitMQBrokerHandler.publishToRabbitMQ("/topic/public", response);
    }

    /**
     * Validates that the user is authenticated (user object exists in WebSocket session).
     * Returns the authenticated user if valid, throws SecurityException if not authenticated.
     * Note: we do NOT check if the user belongs to any group here; that is done in validateSubscription().
     */
    private User validateAuthentication(StompHeaderAccessor accessor) {
        logger.debug("[Start validateAuthentication] Session attributes: {}", accessor.getSessionAttributes());
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

    /**
     * Validates that a user can only subscribe to groups they are members of.
     * Allows subscription to /topic/public without restriction.
     * Rejects subscription to group topics if user is not a member.
     */
    private void validateSubscription(StompHeaderAccessor accessor, User user) {
        logger.debug("[Start validateSubscription] User: {}", user);
        String destination = accessor.getDestination();

        if (destination == null) {
            return; // No destination, nothing to validate
        }

        // Allow subscription to public chat
        if ("/topic/public".equals(destination)) {
            return;
        }

        // Check if subscribing to a group topic (format: /topic/group.{groupId})
        if (destination.startsWith("/topic/group.")) {
            try {
                // Extract group ID from destination (e.g., "/topic/group.1" -> "1")
                String groupIdStr = destination.substring("/topic/group.".length());
                Long groupId = Long.parseLong(groupIdStr);

                groupAuthorizationService.requirePermission(user, groupId, GroupPermission.READ_MESSAGES);
            } catch (NumberFormatException e) {
                throw new SecurityException("Invalid group topic format");
            } catch (NotFoundException | ForbiddenException e) {
                throw new SecurityException(e.getMessage());
            }
        }
        if (destination.startsWith("/topic/user.") && destination.endsWith(".group-updates")) {
            String prefix = "/topic/user.";
            String suffix = ".group-updates";
            String topicUsername = destination.substring(prefix.length(), destination.length() - suffix.length());
            try {
                groupAuthorizationService.requireUserTopicAccess(user, topicUsername);
            } catch (ForbiddenException e) {
                throw new SecurityException(e.getMessage());
            }
        }
        // Allow other topics (if any) - you can add more validation here if needed
    }
}


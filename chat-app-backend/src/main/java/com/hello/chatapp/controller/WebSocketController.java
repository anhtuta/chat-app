package com.hello.chatapp.controller;

import com.hello.chatapp.constant.GroupPermission;
import com.hello.chatapp.config.CustomRabbitMQBrokerHandler;
import com.hello.chatapp.dto.GroupSummaryUpdate;
import com.hello.chatapp.dto.MessageRequest;
import com.hello.chatapp.dto.MessageResponse;
import com.hello.chatapp.entity.Group;
import com.hello.chatapp.entity.Message;
import com.hello.chatapp.entity.User;
import com.hello.chatapp.service.GroupAuthorizationService;
import com.hello.chatapp.service.GroupSummaryUpdatePublisher;
import com.hello.chatapp.service.MessageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.NonNull;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import java.util.Map;
import java.util.Objects;

@Controller
public class WebSocketController {

    private static final Logger logger = LoggerFactory.getLogger(WebSocketController.class);

    private final GroupAuthorizationService groupAuthorizationService;
    private final SimpMessagingTemplate messagingTemplate;
    private final CustomRabbitMQBrokerHandler rabbitMQBrokerHandler;
    private final MessageService messageService;
    private final GroupSummaryUpdatePublisher groupSummaryUpdatePublisher;

    public WebSocketController(GroupAuthorizationService groupAuthorizationService,
            SimpMessagingTemplate messagingTemplate,
            CustomRabbitMQBrokerHandler rabbitMQBrokerHandler,
            MessageService messageService,
            GroupSummaryUpdatePublisher groupSummaryUpdatePublisher) {
        this.groupAuthorizationService = groupAuthorizationService;
        this.messagingTemplate = messagingTemplate;
        this.rabbitMQBrokerHandler = rabbitMQBrokerHandler;
        this.messageService = messageService;
        this.groupSummaryUpdatePublisher = groupSummaryUpdatePublisher;
    }

    @MessageMapping("/chat.send")
    @SendTo("/topic/public")
    @NonNull
    public MessageResponse sendPublicMessage(@Payload @NonNull MessageRequest request,
            SimpMessageHeaderAccessor headerAccessor) {
        User user = getUserFromSession(headerAccessor);
        String content = request.getContent();

        final MessageResponse response;

        // If message is a system message, don't save to database
        if (isSystemMessage(content)) {
            Message message = new Message(user, content);
            message.setGroup(null); // Ensure group is null for public messages
            response = Objects.requireNonNull(MessageResponse.fromMessage(message));
        } else {
            Message savedMessage = messageService.savePublicMessage(user, content);
            response = Objects.requireNonNull(MessageResponse.fromMessage(savedMessage));
        }

        // Publish to RabbitMQ for cross-instance distribution
        // SimpleBroker will handle local delivery via @SendTo
        rabbitMQBrokerHandler.publishToRabbitMQ("/topic/public", response);

        return response;
    }

    @MessageMapping("/group.send")
    @NonNull
    public MessageResponse sendGroupMessage(@Payload @NonNull MessageRequest request,
            SimpMessageHeaderAccessor headerAccessor) {
        logger.debug("[sendGroupMessage] request={}", request);
        User user = getUserFromSession(headerAccessor);
        Group group = validateGroup(request.getGroupId(), user);
        String content = request.getContent();

        String destination = "/topic/group." + group.getId();
        final MessageResponse response;
        Message savedMessage = null;

        // If message is a system message, don't save to database
        if (isSystemMessage(content)) {
            Message message = new Message(user, content);
            message.setGroup(group);
            response = Objects.requireNonNull(MessageResponse.fromMessage(message));
        } else {
            savedMessage = messageService.saveGroupMessage(group, user, content);
            response = Objects.requireNonNull(MessageResponse.fromMessage(savedMessage));
        }

        // Send to local subscribers via SimpleBroker
        logger.debug("[sendGroupMessage] Sending to in-memory broker for local subscribers: destination={}", destination);
        messagingTemplate.convertAndSend(destination, response);

        // Publish to RabbitMQ for cross-instance distribution
        logger.debug("[sendGroupMessage] Publishing to RabbitMQ for cross-instance distribution: destination={}", destination);
        rabbitMQBrokerHandler.publishToRabbitMQ(destination, response);

        // Buffer group-summary updates per group before fan-out so active chats
        // coalesce to at most one personal-topic publish per buffer interval.
        if (savedMessage != null) {
            pushGroupSummaryUpdate(group, savedMessage);
        }

        return response;
    }

    private boolean isSystemMessage(String content) {
        return content != null && content.startsWith("[SYSTEM] ");
    }

    /**
     * Pushes a lightweight group-summary update through the buffered publisher,
     * which later fans out to every member via their personal WebSocket topic.
     * The frontend uses this to refresh the sidebar in real time.
     */
    private void pushGroupSummaryUpdate(Group group, Message savedMessage) {
        Long groupId = Objects.requireNonNull(group.getId());
        String preview = MessageService.buildLatestMessagePreview(savedMessage);
        groupSummaryUpdatePublisher.publishToGroupMembers(
                groupId,
                GroupSummaryUpdate.fromMessage(groupId, savedMessage, preview));
    }

    /**
     * Gets User from WebSocket session attributes.
     * This is set during WebSocket handshake by WebSocketHandshakeInterceptor
     * which extracts it from the HTTP session.
     * 
     * @throws SecurityException if user is not found in session attributes
     */
    private User getUserFromSession(SimpMessageHeaderAccessor headerAccessor) throws SecurityException {
        Map<String, Object> sessionAttributes = headerAccessor.getSessionAttributes();
        if (sessionAttributes != null) {
            User user = (User) sessionAttributes.get("user");
            if (user != null) {
                return user;
            }
        }
        throw new SecurityException("User is not authenticated. Please reconnect.");
    }

    /**
     * Validates that a group exists and that the user is a member of the group.
     * 
     * @throws IllegalArgumentException if the group ID is null
     */
    private Group validateGroup(Long groupId, User user) {
        if (groupId == null) {
            throw new IllegalArgumentException("Group ID is required for group messages");
        }
        return groupAuthorizationService.requirePermission(user, groupId, GroupPermission.SEND_MESSAGES);
    }
}

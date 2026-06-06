package com.hello.chatapp.controller;

import com.hello.chatapp.config.CustomRabbitMQBrokerHandler;
import com.hello.chatapp.dto.GroupSummaryUpdate;
import com.hello.chatapp.dto.MessageRequest;
import com.hello.chatapp.dto.MessageResponse;
import com.hello.chatapp.entity.Group;
import com.hello.chatapp.entity.Message;
import com.hello.chatapp.entity.User;
import com.hello.chatapp.exception.ForbiddenException;
import com.hello.chatapp.exception.NotFoundException;
import com.hello.chatapp.repository.GroupParticipantRepository;
import com.hello.chatapp.repository.GroupRepository;
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

import java.util.List;
import java.util.Map;
import java.util.Objects;

@Controller
public class WebSocketController {

    private static final Logger logger = LoggerFactory.getLogger(WebSocketController.class);

    private final GroupRepository groupRepository;
    private final GroupParticipantRepository groupParticipantRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final CustomRabbitMQBrokerHandler rabbitMQBrokerHandler;
    private final MessageService messageService;

    public WebSocketController(GroupRepository groupRepository,
            GroupParticipantRepository groupParticipantRepository,
            SimpMessagingTemplate messagingTemplate,
            CustomRabbitMQBrokerHandler rabbitMQBrokerHandler,
            MessageService messageService) {
        this.groupRepository = groupRepository;
        this.groupParticipantRepository = groupParticipantRepository;
        this.messagingTemplate = messagingTemplate;
        this.rabbitMQBrokerHandler = rabbitMQBrokerHandler;
        this.messageService = messageService;
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

        // Fan-out group summary update to every member's personal queue so their
        // sidebar refreshes without polling, even when they are not in this group chat.
        if (savedMessage != null) {
            pushGroupSummaryUpdate(group, savedMessage);
        }

        return response;
    }

    private boolean isSystemMessage(String content) {
        return content != null && content.startsWith("[SYSTEM] ");
    }

    /**
     * Pushes a lightweight group-summary update to every member of the group
     * via their personal WebSocket queue (/user/queue/group-updates).
     * The frontend uses this to refresh the sidebar in real time.
     */
    private void pushGroupSummaryUpdate(Group group, Message savedMessage) {
        Long groupId = Objects.requireNonNull(group.getId());
        String preview = MessageService.buildLatestMessagePreview(savedMessage.getContent());
        GroupSummaryUpdate update = GroupSummaryUpdate.fromMessage(groupId, savedMessage, preview);

        // If we use: participants = groupParticipantRepository.findByGroup(group); participant.getUser().getUsername(); then we will have error: org.hibernate.LazyInitializationException: Could not initialize proxy [com.hello.chatapp.entity.User#1] - no session
        // Why: findByGroup returns GroupParticipant entities with lazy User relation, and at that point Hibernate session was not open. So participant.user.username access fails. Solution: query repository for usernames directly without loading the User entities.
        List<String> usernames = groupParticipantRepository.findParticipantUsernamesByGroupId(groupId);
        for (String username : usernames) {
            String safeUsername = Objects.requireNonNull(username);
            String userScopedTopicDestination = "/topic/user." + safeUsername + ".group-updates";

            // Local delivery on current instance.
            messagingTemplate.convertAndSend(userScopedTopicDestination, Objects.requireNonNull((Object) update));

            // Cross-instance delivery via RabbitMQ fanout.
            rabbitMQBrokerHandler.publishToRabbitMQ(userScopedTopicDestination, update);

            logger.debug("[pushGroupSummaryUpdate] Pushed group summary update to user={}, groupId={}, destination={}",
                    safeUsername,
                    groupId,
                    userScopedTopicDestination);
        }
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
     * @throws NotFoundException if the group is not found
     * @throws ForbiddenException if the user is not a member of the group
     */
    private Group validateGroup(Long groupId, User user) throws NotFoundException, ForbiddenException {
        if (groupId == null) {
            throw new IllegalArgumentException("Group ID is required for group messages");
        }
        Group group = groupRepository.findById(groupId)
                .orElseThrow(() -> new NotFoundException("Group with id " + groupId + " not found"));
        if (!groupParticipantRepository.existsByGroupAndUser(group, user)) {
            throw new ForbiddenException("You are not a member of this group");
        }
        return group;
    }
}

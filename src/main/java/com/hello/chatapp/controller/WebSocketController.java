package com.hello.chatapp.controller;

import com.hello.chatapp.config.CustomRabbitMQBrokerHandler;
import com.hello.chatapp.dto.MessageRequest;
import com.hello.chatapp.dto.MessageResponse;
import com.hello.chatapp.entity.Group;
import com.hello.chatapp.entity.Message;
import com.hello.chatapp.entity.User;
import com.hello.chatapp.exception.ForbiddenException;
import com.hello.chatapp.exception.NotFoundException;
import com.hello.chatapp.repository.GroupParticipantRepository;
import com.hello.chatapp.repository.GroupRepository;
import com.hello.chatapp.repository.MessageRepository;
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

@Controller
public class WebSocketController {

    private static final Logger logger = LoggerFactory.getLogger(WebSocketController.class);

    private final MessageRepository messageRepository;
    private final GroupRepository groupRepository;
    private final GroupParticipantRepository groupParticipantRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final CustomRabbitMQBrokerHandler rabbitMQBrokerHandler;

    public WebSocketController(MessageRepository messageRepository,
            GroupRepository groupRepository,
            GroupParticipantRepository groupParticipantRepository,
            SimpMessagingTemplate messagingTemplate,
            CustomRabbitMQBrokerHandler rabbitMQBrokerHandler) {
        this.messageRepository = messageRepository;
        this.groupRepository = groupRepository;
        this.groupParticipantRepository = groupParticipantRepository;
        this.messagingTemplate = messagingTemplate;
        this.rabbitMQBrokerHandler = rabbitMQBrokerHandler;
    }

    @MessageMapping("/chat.send")
    @SendTo("/topic/public")
    @NonNull
    public MessageResponse sendPublicMessage(@Payload @NonNull MessageRequest request,
            SimpMessageHeaderAccessor headerAccessor) {
        User user = getUserFromSession(headerAccessor);
        Message message = new Message(user, request.getContent());
        message.setGroup(null); // Ensure group is null for public messages

        MessageResponse response;

        // If message is a system message, don't save to database
        if (message.getContent() != null && message.getContent().startsWith("[SYSTEM] ")) {
            response = MessageResponse.fromMessage(message);
        } else {
            Message savedMessage = messageRepository.save(message);
            response = MessageResponse.fromMessage(savedMessage);
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
        Message message = new Message(user, request.getContent());
        message.setGroup(group);

        String destination = "/topic/group." + group.getId();
        MessageResponse response;

        // If message is a system message, don't save to database
        if (message.getContent() != null && message.getContent().startsWith("[SYSTEM] ")) {
            response = MessageResponse.fromMessage(message);
        } else {
            Message savedMessage = messageRepository.save(message);
            response = MessageResponse.fromMessage(savedMessage);
        }

        // Send to local subscribers via SimpleBroker
        logger.debug("[sendGroupMessage] Sending to in-memory broker for local subscribers: destination={}", destination);
        messagingTemplate.convertAndSend(destination, response);

        // Publish to RabbitMQ for cross-instance distribution
        logger.debug("[sendGroupMessage] Publishing to RabbitMQ for cross-instance distribution: destination={}", destination);
        rabbitMQBrokerHandler.publishToRabbitMQ(destination, response);

        return response;
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

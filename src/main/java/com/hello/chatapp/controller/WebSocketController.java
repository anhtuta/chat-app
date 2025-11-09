package com.hello.chatapp.controller;

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

    private final MessageRepository messageRepository;
    private final GroupRepository groupRepository;
    private final GroupParticipantRepository groupParticipantRepository;
    private final SimpMessagingTemplate messagingTemplate;

    public WebSocketController(MessageRepository messageRepository,
            GroupRepository groupRepository,
            GroupParticipantRepository groupParticipantRepository,
            SimpMessagingTemplate messagingTemplate) {
        this.messageRepository = messageRepository;
        this.groupRepository = groupRepository;
        this.groupParticipantRepository = groupParticipantRepository;
        this.messagingTemplate = messagingTemplate;
    }

    @MessageMapping("/chat.send")
    @SendTo("/topic/public")
    @NonNull
    public MessageResponse sendPublicMessage(@Payload @NonNull MessageRequest request,
            SimpMessageHeaderAccessor headerAccessor) {
        // Get user from WebSocket session attributes (stored during connection)
        User user = getUserFromSession(headerAccessor);

        if (user == null) {
            throw new SecurityException("User is not authenticated. Please reconnect.");
        }

        // Create message entity
        Message message = new Message(user, request.getContent());
        // Ensure group is null for public messages
        message.setGroup(null);

        // If message is a system message, don't save to database
        if (message.getContent() != null && message.getContent().startsWith("[SYSTEM] ")) {
            return MessageResponse.fromMessage(message);
        }

        // Save message to database
        Message savedMessage = messageRepository.save(message);
        return MessageResponse.fromMessage(savedMessage);
    }

    @MessageMapping("/group.send")
    @NonNull
    public MessageResponse sendGroupMessage(@Payload @NonNull MessageRequest request,
            SimpMessageHeaderAccessor headerAccessor) {
        // Get user from WebSocket session attributes
        User user = getUserFromSession(headerAccessor);

        if (user == null) {
            throw new SecurityException("User is not authenticated. Please reconnect.");
        }

        if (request.getGroupId() == null) {
            throw new IllegalArgumentException("Group ID is required for group messages");
        }

        // Fetch group from database
        // groupId is guaranteed to be non-null after the check above
        Long groupId = request.getGroupId();
        if (groupId == null) {
            throw new IllegalArgumentException("Group ID is required for group messages");
        }
        Group group = groupRepository.findById(groupId)
                .orElseThrow(() -> new NotFoundException("Group with id " + groupId + " not found"));

        // Verify user is a member of the group
        if (!groupParticipantRepository.existsByGroupAndUser(group, user)) {
            throw new ForbiddenException("You are not a member of this group");
        }

        // Create message entity
        Message message = new Message(user, request.getContent());
        message.setGroup(group);

        // If message is a system message, don't save to database
        if (message.getContent() != null && message.getContent().startsWith("[SYSTEM] ")) {
            // Send to group topic
            MessageResponse response = MessageResponse.fromMessage(message);
            messagingTemplate.convertAndSend("/topic/group." + group.getId(), response);
            return response;
        }

        // Save message to database
        Message savedMessage = messageRepository.save(message);
        MessageResponse response = MessageResponse.fromMessage(savedMessage);

        // Send to group topic
        messagingTemplate.convertAndSend("/topic/group." + group.getId(), response);

        return response;
    }

    /**
     * Gets User from WebSocket session attributes.
     * This is set during WebSocket handshake by WebSocketHandshakeInterceptor
     * which extracts it from the HTTP session.
     */
    private User getUserFromSession(SimpMessageHeaderAccessor headerAccessor) {
        Map<String, Object> sessionAttributes = headerAccessor.getSessionAttributes();
        if (sessionAttributes != null) {
            return (User) sessionAttributes.get("user");
        }
        return null;
    }

}

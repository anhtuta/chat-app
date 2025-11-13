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
        User user = getUserFromSession(headerAccessor);
        Message message = new Message(user, request.getContent());
        message.setGroup(null); // Ensure group is null for public messages

        // If message is a system message, don't save to database
        if (message.getContent() != null && message.getContent().startsWith("[SYSTEM] ")) {
            return MessageResponse.fromMessage(message);
        }

        Message savedMessage = messageRepository.save(message);
        return MessageResponse.fromMessage(savedMessage);
    }

    @MessageMapping("/group.send")
    @NonNull
    public MessageResponse sendGroupMessage(@Payload @NonNull MessageRequest request,
            SimpMessageHeaderAccessor headerAccessor) {
        User user = getUserFromSession(headerAccessor);
        Group group = validateGroup(request.getGroupId(), user);
        Message message = new Message(user, request.getContent());
        message.setGroup(group);

        // If message is a system message, don't save to database
        if (message.getContent() != null && message.getContent().startsWith("[SYSTEM] ")) {
            MessageResponse response = MessageResponse.fromMessage(message);
            messagingTemplate.convertAndSend("/topic/group." + group.getId(), response);
            return response;
        }

        Message savedMessage = messageRepository.save(message);
        MessageResponse response = MessageResponse.fromMessage(savedMessage);
        messagingTemplate.convertAndSend("/topic/group." + group.getId(), response);

        return response;
    }

    /**
     * Gets User from WebSocket session attributes.
     * This is set during WebSocket handshake by WebSocketHandshakeInterceptor
     * which extracts it from the HTTP session.
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

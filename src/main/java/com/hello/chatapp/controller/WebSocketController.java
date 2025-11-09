package com.hello.chatapp.controller;

import com.hello.chatapp.entity.Message;
import com.hello.chatapp.entity.User;
import com.hello.chatapp.repository.MessageRepository;
import org.springframework.lang.NonNull;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.stereotype.Controller;

import java.util.Map;

@Controller
public class WebSocketController {

    private final MessageRepository messageRepository;

    public WebSocketController(MessageRepository messageRepository) {
        this.messageRepository = messageRepository;
    }

    @MessageMapping("/chat.send")
    @SendTo("/topic/public")
    @NonNull
    public Message sendMessage(@Payload @NonNull Message message, SimpMessageHeaderAccessor headerAccessor) {
        // Get user from WebSocket session attributes (stored during connection)
        User user = getUserFromSession(headerAccessor);

        if (user == null) {
            throw new SecurityException("User is not authenticated. Please reconnect.");
        }

        // Set user (prevent spoofing)
        message.setUser(user);

        // Save message to database
        return messageRepository.save(message);
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

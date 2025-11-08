package com.hello.chatapp.controller;

import com.hello.chatapp.entity.Message;
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
        // Get username from WebSocket session attributes (stored during connection)
        String authenticatedUsername = getUsernameFromSession(headerAccessor);

        if (authenticatedUsername == null) {
            throw new SecurityException("User is not authenticated. Please reconnect.");
        }

        // Use authenticated username (prevent spoofing)
        message.setSender(authenticatedUsername);

        // Save message to database
        return messageRepository.save(message);
    }

    @MessageMapping("/chat.addUser")
    @SendTo("/topic/public")
    @NonNull
    public Message addUser(@Payload @NonNull Message message, SimpMessageHeaderAccessor headerAccessor) {
        // Get username from WebSocket session attributes (set during handshake)
        String username = getUsernameFromSession(headerAccessor);

        if (username == null) {
            throw new SecurityException("User is not authenticated. Please login and reconnect.");
        }

        // Use authenticated username (prevent spoofing)
        message.setSender(username);

        return message;
    }

    /**
     * Gets username from WebSocket session attributes.
     * This is set during WebSocket handshake by WebSocketHandshakeInterceptor
     * which extracts it from the HTTP session.
     */
    private String getUsernameFromSession(SimpMessageHeaderAccessor headerAccessor) {
        Map<String, Object> sessionAttributes = headerAccessor.getSessionAttributes();
        if (sessionAttributes != null) {
            return (String) sessionAttributes.get("username");
        }
        return null;
    }

}

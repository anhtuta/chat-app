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
    public Message sendMessage(@Payload @NonNull Message message) {
        // Save message to database
        return messageRepository.save(message);
    }

    @MessageMapping("/chat.addUser")
    @SendTo("/topic/public")
    @NonNull
    public Message addUser(@Payload @NonNull Message message, SimpMessageHeaderAccessor headerAccessor) {
        // Add username in websocket session
        Map<String, Object> sessionAttributes = headerAccessor.getSessionAttributes();
        if (sessionAttributes != null && message.getSender() != null) {
            sessionAttributes.put("username", message.getSender());
        }
        return message;
    }
}

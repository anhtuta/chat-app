package com.hello.chatapp.controller;

import com.hello.chatapp.entity.Message;
import com.hello.chatapp.repository.MessageRepository;
import org.springframework.lang.NonNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/chat")
public class MessageController {

    private final MessageRepository messageRepository;

    public MessageController(MessageRepository messageRepository) {
        this.messageRepository = messageRepository;
    }

    @GetMapping("/messages")
    public List<Message> getAllMessages() {
        try {
            // Simulate a delay to test the loading indicator
            Thread.sleep(500);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        return messageRepository.findAllByOrderByTimestampAsc();
    }

    @PostMapping("/messages")
    @NonNull
    public Message createMessage(@RequestBody @NonNull Message message) {
        return messageRepository.save(message);
    }
}


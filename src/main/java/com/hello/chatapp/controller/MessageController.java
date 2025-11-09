package com.hello.chatapp.controller;

import com.hello.chatapp.dto.MessageResponse;
import com.hello.chatapp.entity.Group;
import com.hello.chatapp.entity.User;
import com.hello.chatapp.exception.ForbiddenException;
import com.hello.chatapp.exception.NotFoundException;
import com.hello.chatapp.exception.UnauthorizedException;
import com.hello.chatapp.repository.GroupParticipantRepository;
import com.hello.chatapp.repository.GroupRepository;
import com.hello.chatapp.repository.MessageRepository;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.NonNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/chat")
public class MessageController {

    private final MessageRepository messageRepository;
    private final GroupRepository groupRepository;
    private final GroupParticipantRepository groupParticipantRepository;

    public MessageController(MessageRepository messageRepository,
            GroupRepository groupRepository,
            GroupParticipantRepository groupParticipantRepository) {
        this.messageRepository = messageRepository;
        this.groupRepository = groupRepository;
        this.groupParticipantRepository = groupParticipantRepository;
    }

    @GetMapping("/messages")
    public List<MessageResponse> getPublicMessages() {
        fakeDelay();
        return messageRepository.findAllPublicMessages().stream()
                .map(MessageResponse::fromMessage)
                .collect(Collectors.toList());
    }

    @GetMapping("/groups/{groupId}/messages")
    public ResponseEntity<List<MessageResponse>> getGroupMessages(@PathVariable @NonNull Long groupId, HttpSession session) {
        fakeDelay();

        // Get authenticated user from session
        User user = (User) session.getAttribute("user");
        if (user == null) {
            throw new UnauthorizedException("User is not authenticated");
        }

        // Find group
        Group group = groupRepository.findById(groupId)
                .orElseThrow(() -> new NotFoundException("Group with id " + groupId + " not found"));

        // Check if user is a member of the group
        if (!groupParticipantRepository.existsByGroupAndUser(group, user)) {
            throw new ForbiddenException("You are not a member of this group");
        }

        List<MessageResponse> messages = messageRepository.findByGroupOrderByTimestampAsc(group).stream()
                .map(MessageResponse::fromMessage)
                .collect(Collectors.toList());
        return ResponseEntity.ok(messages);
    }

    private void fakeDelay() {
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
}

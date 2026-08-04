package com.hello.chatapp.controller;

import com.hello.chatapp.constant.GroupPermission;
import com.hello.chatapp.dto.MessageResponse;
import com.hello.chatapp.dto.UpdateMessageRequest;
import com.hello.chatapp.entity.Group;
import com.hello.chatapp.entity.User;
import com.hello.chatapp.exception.BadRequestException;
import com.hello.chatapp.exception.UnauthorizedException;
import com.hello.chatapp.service.GroupAuthorizationService;
import com.hello.chatapp.service.MessageHistoryService;
import com.hello.chatapp.service.MessageModerationService;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.NonNull;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/messages")
public class MessageController {

    private final GroupAuthorizationService groupAuthorizationService;
    private final MessageHistoryService messageHistoryService;
    private final MessageModerationService messageModerationService;

    public MessageController(GroupAuthorizationService groupAuthorizationService,
            MessageHistoryService messageHistoryService,
            MessageModerationService messageModerationService) {
        this.groupAuthorizationService = groupAuthorizationService;
        this.messageHistoryService = messageHistoryService;
        this.messageModerationService = messageModerationService;
    }

    @GetMapping("/public")
    public List<MessageResponse> getPublicMessages() {
        fakeDelay();
        return messageHistoryService.getPublicMessages();
    }

    @GetMapping("/groups/{groupId}")
    public ResponseEntity<List<MessageResponse>> getGroupMessages(
            @PathVariable @NonNull Long groupId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime beforeTimestamp,
            @RequestParam(required = false) Long beforeId,
            @RequestParam(defaultValue = "10") int size,
            HttpSession session) {
        fakeDelay();

        User user = getAuthenticatedUser(session);
        Group group = groupAuthorizationService.requirePermission(user, groupId, GroupPermission.READ_MESSAGES);

        int validatedSize = Math.min(Math.max(size, 1), 100);
        boolean hasCursorTimestamp = beforeTimestamp != null;
        boolean hasCursorId = beforeId != null;

        if (hasCursorTimestamp != hasCursorId) {
            throw new BadRequestException("Both beforeTimestamp and beforeId are required when using cursor pagination");
        }

        return ResponseEntity.ok(messageHistoryService.getGroupMessages(group, beforeTimestamp, beforeId, validatedSize));
    }

    @PatchMapping("/{messageId}")
    public ResponseEntity<MessageResponse> editMessage(
            @PathVariable Long messageId,
            @Valid @RequestBody UpdateMessageRequest request,
            HttpSession session) {
        return ResponseEntity.ok(messageModerationService.editMessage(
                getAuthenticatedUser(session),
                messageId,
                request.getContent()));
    }

    @DeleteMapping("/{messageId}")
    public ResponseEntity<MessageResponse> deleteMessage(@PathVariable Long messageId, HttpSession session) {
        return ResponseEntity.ok(messageModerationService.deleteMessage(getAuthenticatedUser(session), messageId));
    }

    private User getAuthenticatedUser(HttpSession session) {
        User user = (User) session.getAttribute("user");
        if (user == null) {
            throw new UnauthorizedException("User is not authenticated");
        }
        return user;
    }

    private void fakeDelay() {
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
}

package com.hello.chatapp.service;

import com.hello.chatapp.dto.MessageResponse;
import com.hello.chatapp.dto.MessageResponseMapper;
import com.hello.chatapp.entity.Message;
import com.hello.chatapp.entity.MessageEditHistory;
import com.hello.chatapp.entity.User;
import com.hello.chatapp.exception.BadRequestException;
import com.hello.chatapp.exception.NotFoundException;
import com.hello.chatapp.repository.MessageEditHistoryRepository;
import com.hello.chatapp.repository.MessageRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Objects;

@Service
public class MessageModerationService {

    private final MessageRepository messageRepository;
    private final MessageEditHistoryRepository messageEditHistoryRepository;
    private final GroupAuthorizationService groupAuthorizationService;
    private final MessageResponseMapper messageResponseMapper;
    private final MessageService messageService;

    public MessageModerationService(
            MessageRepository messageRepository,
            MessageEditHistoryRepository messageEditHistoryRepository,
            GroupAuthorizationService groupAuthorizationService,
            MessageResponseMapper messageResponseMapper,
            MessageService messageService) {
        this.messageRepository = messageRepository;
        this.messageEditHistoryRepository = messageEditHistoryRepository;
        this.groupAuthorizationService = groupAuthorizationService;
        this.messageResponseMapper = messageResponseMapper;
        this.messageService = messageService;
    }

    @Transactional
    public MessageResponse editMessage(User actor, Long messageId, String content) {
        Message message = loadMessage(messageId);
        groupAuthorizationService.requireCanEditMessage(actor, message);
        if (message.getDeletedAt() != null) {
            throw new BadRequestException("Deleted messages cannot be edited");
        }

        String normalizedContent = normalizeContent(content);
        if (Objects.equals(message.getContent(), normalizedContent)) {
            throw new BadRequestException("Message content is unchanged");
        }

        MessageEditHistory editHistory = new MessageEditHistory();
        editHistory.setMessage(message);
        editHistory.setOldContent(message.getContent());
        editHistory.setUpdatedBy(actor);
        messageEditHistoryRepository.save(editHistory);

        message.setContent(normalizedContent);
        message.setUpdatedBy(actor);
        message.setUpdatedAt(LocalDateTime.now());

        Message savedMessage = messageRepository.save(message);
        // Map before refresh: refreshGroupLatestMessage may run @Modifying(clearAutomatically=true)
        // and detach this entity, which would LazyInitializationException on user/group/etc.
        MessageResponse response = messageResponseMapper.toResponse(savedMessage);
        refreshGroupSummaryIfNeeded(savedMessage);
        return response;
    }

    @Transactional
    public MessageResponse deleteMessage(User actor, Long messageId) {
        Message message = loadMessage(messageId);
        groupAuthorizationService.requireCanDeleteMessage(actor, message);
        if (message.getDeletedAt() != null) {
            throw new BadRequestException("Message is already deleted");
        }

        message.setDeletedBy(actor);
        message.setDeletedAt(LocalDateTime.now());

        Message savedMessage = messageRepository.save(message);
        MessageResponse response = messageResponseMapper.toResponse(savedMessage);
        refreshGroupSummaryIfNeeded(savedMessage);
        return response;
    }

    private Message loadMessage(Long messageId) {
        Long safeMessageId = Objects.requireNonNull(messageId, "messageId must not be null");
        return messageRepository.findWithMediaById(safeMessageId)
                .orElseThrow(() -> new NotFoundException("Message with id " + safeMessageId + " not found"));
    }

    private String normalizeContent(String content) {
        String normalizedContent = Objects.requireNonNull(content, "content must not be null").trim();
        if (normalizedContent.isEmpty()) {
            throw new BadRequestException("content is required");
        }
        return normalizedContent;
    }

    private void refreshGroupSummaryIfNeeded(Message message) {
        if (message.getGroup() == null || message.getGroup().getId() == null) {
            return;
        }
        messageService.refreshGroupLatestMessage(message.getGroup().getId(), message.getId());
    }
}

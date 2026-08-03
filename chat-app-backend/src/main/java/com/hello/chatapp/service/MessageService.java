package com.hello.chatapp.service;

import com.hello.chatapp.constant.SystemEventType;
import com.hello.chatapp.constant.MessageType;
import com.hello.chatapp.entity.Group;
import com.hello.chatapp.entity.Message;
import com.hello.chatapp.entity.MessageMedia;
import com.hello.chatapp.entity.User;
import com.hello.chatapp.exception.NotFoundException;
import com.hello.chatapp.repository.GroupRepository;
import com.hello.chatapp.repository.MessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.List;

@Service
public class MessageService {

    private static final Logger logger = LoggerFactory.getLogger(MessageService.class);
    private static final int LATEST_MESSAGE_MAX_LENGTH = 255;

    private final MessageRepository messageRepository;
    private final GroupRepository groupRepository;

    public MessageService(
            MessageRepository messageRepository,
            GroupRepository groupRepository) {
        this.messageRepository = messageRepository;
        this.groupRepository = groupRepository;
    }

    @Transactional
    public Message savePublicMessage(User user, String content) {
        Objects.requireNonNull(user.getId());

        Message message = new Message(user, content);
        message.setGroup(null);
        return messageRepository.save(message);
    }

    @Transactional
    public Message savePublicMediaMessage(User user, MessageType messageType, List<MessageMedia> attachments) {
        Objects.requireNonNull(user.getId());
        Message message = new Message();
        message.setUser(user);
        message.setGroup(null);
        message.setMessageType(messageType);
        message.setContent(null);
        attachMedia(message, attachments);
        return messageRepository.save(message);
    }

    /**
     * Saves a new message to the database and updates the group's latest message summary.
     * It will modify both messages and groups tables.
     * How it works:
     * 1. Load the group to ensure it exists and to attach a managed entity to the message.
     * 2. Save the new message to the database.
     * 3. Try a compare-and-set update on group summary fields.
     * 4. If compare-and-set doesn't update any row, another concurrent transaction already wrote a newer latest message.
     *
     * This avoids a pessimistic lock on the group row while still preventing stale summary updates.
     */
    @Transactional
    public Message saveGroupMessage(Group group, User user, String content) {
        Long groupId = Objects.requireNonNull(group.getId());
        Objects.requireNonNull(user.getId());
        Group existingGroup = groupRepository.findById(groupId)
                .orElseThrow(() -> new NotFoundException("Group with id " + groupId + " not found"));

        Message message = new Message(user, content);
        message.setGroup(existingGroup);

        Message savedMessage = messageRepository.saveAndFlush(message);

        // Sleep is intentionally kept for demo timing to simulate a slower request.
        try {
            logger.debug("Saved message for group {}, sleeping to simulate long request...", groupId);
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // TODO remove the sleep

        int rowsUpdated = groupRepository.updateLatestMessageIfNewer(
                groupId,
                buildLatestMessagePreview(savedMessage.getContent()),
                savedMessage.getUser().getUsername(),
                savedMessage.getTimestamp(),
                Objects.requireNonNull(savedMessage.getId()));

        if (rowsUpdated == 0) {
            logger.debug("Skipped latest-message update for group {} because a newer/equal latest message already exists",
                    groupId);
        }

        return savedMessage;
    }

    @Transactional
    public Message saveGroupMediaMessage(Group group, User user, MessageType messageType, List<MessageMedia> attachments) {
        Long groupId = Objects.requireNonNull(group.getId());
        Objects.requireNonNull(user.getId());
        Group existingGroup = groupRepository.findById(groupId)
                .orElseThrow(() -> new NotFoundException("Group with id " + groupId + " not found"));

        Message message = new Message();
        message.setUser(user);
        message.setGroup(existingGroup);
        message.setMessageType(messageType);
        message.setContent(null);
        attachMedia(message, attachments);

        Message savedMessage = messageRepository.saveAndFlush(message);

        // Sleep is intentionally kept for demo timing to simulate a slower request.
        try {
            logger.debug("Saved media message for group {}, sleeping to simulate long request...", groupId);
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // TODO remove the sleep

        int rowsUpdated = groupRepository.updateLatestMessageIfNewer(
                groupId,
                buildLatestMessagePreview(savedMessage),
                savedMessage.getUser().getUsername(),
                savedMessage.getTimestamp(),
                Objects.requireNonNull(savedMessage.getId()));

        if (rowsUpdated == 0) {
            logger.debug("Skipped latest-message update for group {} because a newer/equal latest message already exists",
                    groupId);
        }

        return savedMessage;
    }

    @Transactional
    public Message saveGroupSystemMessage(
            Group group,
            User subjectUser,
            User actor,
            SystemEventType eventType) {
        Long groupId = Objects.requireNonNull(group.getId());
        SystemEventType safeEventType = Objects.requireNonNull(eventType, "eventType must not be null");
        Group existingGroup = groupRepository.findById(groupId)
                .orElseThrow(() -> new NotFoundException("Group with id " + groupId + " not found"));

        Message message = new Message();
        message.setUser(Objects.requireNonNull(subjectUser, "subjectUser must not be null"));
        message.setUpdatedBy(actor);
        message.setGroup(existingGroup);
        message.setMessageType(MessageType.SYSTEM);
        message.setContent(safeEventType.name());

        Message savedMessage = messageRepository.saveAndFlush(message);
        updateLatestMessageSummary(
                groupId,
                safeEventType.latestPreview(),
                "System",
                savedMessage.getTimestamp(),
                Objects.requireNonNull(savedMessage.getId()));
        return savedMessage;
    }

    public static String buildLatestMessagePreview(String content) {
        if (content == null) {
            return null;
        }
        String normalized = content.trim();
        if (normalized.length() <= LATEST_MESSAGE_MAX_LENGTH) {
            return normalized;
        }
        return normalized.substring(0, LATEST_MESSAGE_MAX_LENGTH);
    }

    public static String buildLatestMessagePreview(Message message) {
        if (message == null) {
            return null;
        }
        if (message.getDeletedAt() != null) {
            return "Message deleted";
        }
        MessageType messageType = message.getMessageType();
        if (messageType == null || messageType == MessageType.TEXT || messageType == MessageType.SYSTEM) {
            return buildLatestMessagePreview(message.getContent());
        }

        return switch (messageType) {
            case IMAGE -> message.getAttachments().size() > 1 ? "Photos" : "Photo";
            case VIDEO -> "Video";
            case AUDIO -> "Audio";
            case FILE -> message.getAttachments().isEmpty()
                    ? "File"
                    : buildLatestMessagePreview(message.getAttachments().getFirst().getOriginalFilename());
            default -> buildLatestMessagePreview(message.getContent());
        };
    }

    @Transactional
    public void refreshGroupLatestMessage(Long groupId) {
        Long safeGroupId = Objects.requireNonNull(groupId, "groupId must not be null");
        Group group = groupRepository.findById(safeGroupId)
                .orElseThrow(() -> new NotFoundException("Group with id " + safeGroupId + " not found"));

        java.util.Optional<Message> latestMessage = messageRepository.findTopByGroup_IdOrderByTimestampDescIdDesc(safeGroupId);
        if (latestMessage.isEmpty()) {
            group.setLatestMessage(null);
            group.setLatestMessageSender(null);
            group.setLatestMessageAt(null);
            groupRepository.save(group);
            return;
        }

        Message latest = latestMessage.get();
        group.setLatestMessage(buildLatestMessagePreview(latest));
        group.setLatestMessageSender(latest.getMessageType() == MessageType.SYSTEM ? "System" : latest.getUser().getUsername());
        group.setLatestMessageAt(latest.getTimestamp());
        groupRepository.save(group);
    }

    private void updateLatestMessageSummary(
            Long groupId,
            String latestMessagePreview,
            String latestMessageSender,
            java.time.LocalDateTime latestMessageAt,
            Long messageId) {
        int rowsUpdated = groupRepository.updateLatestMessageIfNewer(
                groupId,
                latestMessagePreview,
                latestMessageSender,
                latestMessageAt,
                messageId);

        if (rowsUpdated == 0) {
            logger.debug("Skipped latest-message update for group {} because a newer/equal latest message already exists",
                    groupId);
        }
    }

    private void attachMedia(Message message, List<MessageMedia> attachments) {
        if (attachments == null) {
            return;
        }
        for (MessageMedia attachment : attachments) {
            message.addAttachment(attachment);
        }
    }
}

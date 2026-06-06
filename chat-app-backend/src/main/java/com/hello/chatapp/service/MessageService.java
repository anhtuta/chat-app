package com.hello.chatapp.service;

import com.hello.chatapp.entity.Group;
import com.hello.chatapp.entity.Message;
import com.hello.chatapp.entity.User;
import com.hello.chatapp.exception.NotFoundException;
import com.hello.chatapp.repository.GroupRepository;
import com.hello.chatapp.repository.MessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

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
            logger.debug("Skipped latest-message update for group {} because a newer/equal latest message already exists", groupId);
        }

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
}

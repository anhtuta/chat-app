package com.hello.chatapp.service;

import com.hello.chatapp.entity.Group;
import com.hello.chatapp.entity.Message;
import com.hello.chatapp.entity.User;
import com.hello.chatapp.exception.NotFoundException;
import com.hello.chatapp.repository.GroupRepository;
import com.hello.chatapp.repository.MessageRepository;
import com.hello.chatapp.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

@Service
public class MessageService {

    private static final Logger logger = LoggerFactory.getLogger(MessageService.class);
    private static final int LATEST_MESSAGE_MAX_LENGTH = 255;

    private final MessageRepository messageRepository;
    private final GroupRepository groupRepository;
    private final UserRepository userRepository;

    public MessageService(
            MessageRepository messageRepository,
            GroupRepository groupRepository,
            UserRepository userRepository) {
        this.messageRepository = messageRepository;
        this.groupRepository = groupRepository;
        this.userRepository = userRepository;
    }

    @Transactional
    public Message savePublicMessage(User user, String content) {
        Long userId = Objects.requireNonNull(user.getId());
        User messageUser = userRepository.getReferenceById(userId);

        Message message = new Message(messageUser, content);
        message.setGroup(null);
        return messageRepository.save(message);
    }

    /**
     * Saves a new message to the database and updates the group's latest message summary.
     * It will modify both messages and groups tables.
     * How it works:
     * 1. Lock the group row first to prevent concurrent updates to the same group.
     * 2. Save the new message to the database (while holding the lock).
     * 3. Update the group's latest message summary based on the message we just saved.
     * 4. When the transaction commits, any changes to the locked group entity will be persisted to the database.
     *
     * The pessimistic lock ensures that only one transaction can update the group's summary fields at a time.
     * Therefore, the message we save is guaranteed to be the latest, and we apply its values directly to the group.
     */
    @Transactional
    public Message saveGroupMessage(Group group, User user, String content) {
        Long groupId = Objects.requireNonNull(group.getId());
        Long userId = Objects.requireNonNull(user.getId());
        Group lockedGroup = groupRepository.findByIdForLatestMessageUpdate(groupId)
                .orElseThrow(() -> new NotFoundException("Group with id " + groupId + " not found"));
        User messageUser = userRepository.getReferenceById(userId);

        Message message = new Message(messageUser, content);
        message.setGroup(lockedGroup);

        Message savedMessage = messageRepository.saveAndFlush(message);

        // After locking the group, sleep to simulate a long-running transaction and increase the chance of concurrent updates
        try {
            logger.debug("Acquired lock on group {}, sleeping to simulate long transaction...", groupId);
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // TODO remove the sleep

        // The pessimistic lock ensures no other transaction can write to this group while we hold it.
        // Therefore, the message we just saved is guaranteed to be the latest for this group.
        // When the transaction commits, any changes to lockedGroup entity will be persisted to the database.
        // So we don't need to call groupRepository.save(lockedGroup) explicitly.
        applyLatestMessage(lockedGroup, savedMessage);

        return savedMessage;
    }

    private void applyLatestMessage(Group group, Message message) {
        group.setLatestMessage(buildLatestMessagePreview(message.getContent()));
        group.setLatestMessageSender(message.getUser().getUsername());
        group.setLatestMessageAt(message.getTimestamp());
    }

    static String buildLatestMessagePreview(String content) {
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

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

    /**
     * Saves a new message to the database and updates the group's latest message summary.
     * It will modify both messages and groups tables.
     */
    @Transactional
    public Message saveGroupMessage(Group group, User user, String content) {
        Long groupId = Objects.requireNonNull(group.getId());
        Long userId = Objects.requireNonNull(user.getId());
        Group messageGroup = groupRepository.getReferenceById(groupId);
        User messageUser = userRepository.getReferenceById(userId);

        Message message = new Message(messageUser, content);
        message.setGroup(messageGroup);

        Message savedMessage = messageRepository.saveAndFlush(message);
        Group lockedGroup = groupRepository.findByIdForLatestMessageUpdate(groupId)
                .orElseThrow(() -> new NotFoundException("Group with id " + groupId + " not found"));

        // We have two different Java objects, even though they represent the same database row: messageGroup and lockedGroup.
        // messageGroup = a proxy/reference created within the current transaction.
        // lockedGroup = a fresh entity loaded and LOCKED within the current transaction.

        // After locking the group, sleep to simulate a long-running transaction and increase the chance of concurrent updates
        try {
            logger.debug("Acquired lock on group {}, sleeping to simulate long transaction...", groupId);
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // TODO remove the sleep

        Message latestMessage = messageRepository.findLatestGroupMessages(lockedGroup, PageRequest.of(0, 1)).stream()
                .findFirst()
                .orElse(savedMessage);

        // When the transaction commits, any changes to lockedGroup entity will be persisted to the database.
        // So we don't need to call groupRepository.save(lockedGroup) explicitly.
        // Updating group is more of a convenience for the caller: it's good practice to keep both in sync so
        // the caller's object reference doesn't become stale.
        applyLatestMessage(lockedGroup, latestMessage);
        applyLatestMessage(group, latestMessage);

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

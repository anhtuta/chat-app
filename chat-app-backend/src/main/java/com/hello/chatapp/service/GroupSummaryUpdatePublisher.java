package com.hello.chatapp.service;

import com.hello.chatapp.config.CustomRabbitMQBrokerHandler;
import com.hello.chatapp.dto.GroupSummaryUpdate;
import com.hello.chatapp.repository.GroupParticipantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

@Service
public class GroupSummaryUpdatePublisher {

    private static final Logger logger = LoggerFactory.getLogger(GroupSummaryUpdatePublisher.class);

    private final GroupParticipantRepository groupParticipantRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final CustomRabbitMQBrokerHandler rabbitMQBrokerHandler;

    public GroupSummaryUpdatePublisher(GroupParticipantRepository groupParticipantRepository,
            SimpMessagingTemplate messagingTemplate,
            CustomRabbitMQBrokerHandler rabbitMQBrokerHandler) {
        this.groupParticipantRepository = groupParticipantRepository;
        this.messagingTemplate = messagingTemplate;
        this.rabbitMQBrokerHandler = rabbitMQBrokerHandler;
    }

    @Async("groupSummaryUpdateExecutor")
    public void publishToGroupMembers(Long groupId, GroupSummaryUpdate update) {
        try {
            List<String> usernames = groupParticipantRepository.findParticipantUsernamesByGroupId(groupId);
            for (String username : usernames) {
                String safeUsername = Objects.requireNonNull(username);
                String userScopedTopicDestination = "/topic/user." + safeUsername + ".group-updates";

                // Local delivery on current instance.
                messagingTemplate.convertAndSend(userScopedTopicDestination, Objects.requireNonNull((Object) update));

                // Cross-instance delivery via RabbitMQ.
                rabbitMQBrokerHandler.publishToRabbitMQ(userScopedTopicDestination, update);

                logger.debug(
                        "[publishToGroupMembers] Pushed group summary update to user={}, groupId={}, destination={}, message={}",
                        safeUsername,
                        groupId,
                        userScopedTopicDestination,
                        update.toString());
            }
        } catch (Exception e) {
            logger.error("Failed to publish group summary update asynchronously: groupId={}", groupId, e);
        }
    }
}

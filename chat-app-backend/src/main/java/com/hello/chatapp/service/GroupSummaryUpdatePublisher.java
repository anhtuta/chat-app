package com.hello.chatapp.service;

import com.hello.chatapp.config.CustomRabbitMQBrokerHandler;
import com.hello.chatapp.dto.GroupSummaryUpdate;
import com.hello.chatapp.repository.GroupParticipantRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledFuture;

@Service
public class GroupSummaryUpdatePublisher {

    private static final Logger logger = LoggerFactory.getLogger(GroupSummaryUpdatePublisher.class);
    private static final Duration GROUP_SUMMARY_DEBOUNCE_WINDOW = Duration.ofMillis(10000);

    private final GroupParticipantRepository groupParticipantRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final CustomRabbitMQBrokerHandler rabbitMQBrokerHandler;
    private final TaskScheduler groupSummaryUpdateScheduler;
    private final ConcurrentMap<Long, PendingGroupSummaryUpdate> pendingUpdates = new ConcurrentHashMap<>();

    public GroupSummaryUpdatePublisher(GroupParticipantRepository groupParticipantRepository,
            SimpMessagingTemplate messagingTemplate,
            CustomRabbitMQBrokerHandler rabbitMQBrokerHandler,
            @Qualifier("groupSummaryUpdateScheduler") TaskScheduler groupSummaryUpdateScheduler) {
        this.groupParticipantRepository = groupParticipantRepository;
        this.messagingTemplate = messagingTemplate;
        this.rabbitMQBrokerHandler = rabbitMQBrokerHandler;
        this.groupSummaryUpdateScheduler = groupSummaryUpdateScheduler;
    }

    public void publishToGroupMembers(Long groupId, GroupSummaryUpdate update) {
        Long safeGroupId = Objects.requireNonNull(groupId);
        GroupSummaryUpdate safeUpdate = Objects.requireNonNull(update);

        PendingGroupSummaryUpdate pendingUpdate = pendingUpdates.computeIfAbsent(
                safeGroupId,
                ignored -> new PendingGroupSummaryUpdate());

        synchronized (pendingUpdate) {
            pendingUpdate.latestUpdate = safeUpdate;

            if (pendingUpdate.scheduledFlush != null) {
                pendingUpdate.scheduledFlush.cancel(false);
            }

            Instant flushAt = Objects.requireNonNull(Instant.now().plus(GROUP_SUMMARY_DEBOUNCE_WINDOW));
            pendingUpdate.scheduledFlush = groupSummaryUpdateScheduler.schedule(
                    () -> flushGroupMembers(safeGroupId, pendingUpdate),
                    flushAt);
        }

        logger.debug(
                "[publishToGroupMembers] Buffered group summary update for groupId={} with debounce={}ms, message={}",
                safeGroupId,
                GROUP_SUMMARY_DEBOUNCE_WINDOW.toMillis(),
                safeUpdate);
    }

    private void flushGroupMembers(Long groupId, PendingGroupSummaryUpdate pendingUpdate) {
        GroupSummaryUpdate updateToPublish;

        synchronized (pendingUpdate) {
            updateToPublish = pendingUpdate.latestUpdate;
            pendingUpdate.scheduledFlush = null;
        }

        if (updateToPublish == null) {
            pendingUpdates.remove(groupId, pendingUpdate);
            return;
        }

        try {
            List<String> usernames = groupParticipantRepository.findParticipantUsernamesByGroupId(groupId);
            logger.debug(
                    "[flushGroupMembers] Flushed debounced group summary update to {} users in groupId={}, message={}",
                    usernames.size(),
                    groupId,
                    updateToPublish);
            for (String username : usernames) {
                String safeUsername = Objects.requireNonNull(username);
                String userScopedTopicDestination = "/topic/user." + safeUsername + ".group-updates";

                // Local delivery on current instance.
                messagingTemplate.convertAndSend(userScopedTopicDestination, updateToPublish);

                // Cross-instance delivery via RabbitMQ.
                rabbitMQBrokerHandler.publishToRabbitMQ(userScopedTopicDestination, updateToPublish);
            }
        } catch (Exception e) {
            logger.error("Failed to flush debounced group summary update: groupId={}", groupId, e);
        } finally {
            synchronized (pendingUpdate) {
                if (pendingUpdate.scheduledFlush == null
                        && Objects.equals(pendingUpdate.latestUpdate, updateToPublish)) {
                    pendingUpdate.latestUpdate = null;
                    pendingUpdates.remove(groupId, pendingUpdate);
                }
            }
        }
    }

    private static final class PendingGroupSummaryUpdate {
        private GroupSummaryUpdate latestUpdate;
        private ScheduledFuture<?> scheduledFlush;
    }
}

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

/**
 * Debounces group-summary fan-out per {@code groupId}.
 * <p>
 * Buffering is per-group, not global: each group keeps its own {@code latestUpdate} and flush
 * timer. {@link #GROUP_SUMMARY_BUFFER_INTERVAL} is measured from the first update in a burst for
 * that group only. Groups do not share flush timestamps — e.g. group1 may flush at 10:01:04 while
 * group2 flushes at 10:01:05 if its first buffered update arrived at 10:01:02.
 */
@Service
public class GroupSummaryUpdatePublisher {

    private static final Logger logger = LoggerFactory.getLogger(GroupSummaryUpdatePublisher.class);
    // todo is 3s enough? should we cache the member list in redis?
    /** Debounce delay applied independently per group after the first update in a burst. */
    private static final Duration GROUP_SUMMARY_BUFFER_INTERVAL = Duration.ofMillis(3000);

    private final GroupParticipantRepository groupParticipantRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final CustomRabbitMQBrokerHandler rabbitMQBrokerHandler;
    private final GroupUpdatesSubscriptionRegistry groupUpdatesSubscriptionRegistry;
    private final TaskScheduler groupSummaryUpdateScheduler;
    /** Per-group debounce state; map keys are not synchronized to a shared flush clock. */
    private final ConcurrentMap<Long, PendingGroupSummaryUpdate> pendingUpdates = new ConcurrentHashMap<>();

    public GroupSummaryUpdatePublisher(GroupParticipantRepository groupParticipantRepository,
            SimpMessagingTemplate messagingTemplate,
            CustomRabbitMQBrokerHandler rabbitMQBrokerHandler,
            GroupUpdatesSubscriptionRegistry groupUpdatesSubscriptionRegistry,
            @Qualifier("groupSummaryUpdateScheduler") TaskScheduler groupSummaryUpdateScheduler) {
        this.groupParticipantRepository = groupParticipantRepository;
        this.messagingTemplate = messagingTemplate;
        this.rabbitMQBrokerHandler = rabbitMQBrokerHandler;
        this.groupUpdatesSubscriptionRegistry = groupUpdatesSubscriptionRegistry;
        this.groupSummaryUpdateScheduler = groupSummaryUpdateScheduler;
    }

    /**
     * Using pendingUpdate as the lock gives per-group serialization:
     * - different groups run in parallel;
     * - only the same groupId is serialized.
     * (See spring.md for more details)
     */
    public void publishToGroupMembers(Long groupId, GroupSummaryUpdate update) {
        Long safeGroupId = Objects.requireNonNull(groupId);
        GroupSummaryUpdate safeUpdate = Objects.requireNonNull(update);

        PendingGroupSummaryUpdate pendingUpdate = pendingUpdates.computeIfAbsent(
                safeGroupId,
                ignored -> new PendingGroupSummaryUpdate());

        synchronized (pendingUpdate) {
            pendingUpdate.latestUpdate = safeUpdate;
            scheduleFlushIfAbsent(safeGroupId, pendingUpdate);
        }

        logger.trace(
                "[publishToGroupMembers] Buffered group summary update for groupId={} with bufferInterval={}ms, message={}",
                safeGroupId,
                GROUP_SUMMARY_BUFFER_INTERVAL.toMillis(),
                safeUpdate);
    }

    /** Schedules one flush for this group; further updates before flush only coalesce into {@code latestUpdate}. */
    private void scheduleFlushIfAbsent(Long groupId, PendingGroupSummaryUpdate pendingUpdate) {
        if (pendingUpdate.scheduledFlush != null || pendingUpdate.latestUpdate == null) {
            return;
        }

        // Relative to this group's first update in the current burst, not a global tick.
        Instant flushAt = Objects.requireNonNull(Instant.now().plus(GROUP_SUMMARY_BUFFER_INTERVAL));
        pendingUpdate.scheduledFlush = groupSummaryUpdateScheduler.schedule(
                () -> flushGroupMembers(groupId, pendingUpdate),
                flushAt);
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
            int deliveredCount = 0;
            for (String username : usernames) {
                String safeUsername = Objects.requireNonNull(username);

                // Check if the user is online on any instance
                if (!groupUpdatesSubscriptionRegistry.hasClusterSubscriber(safeUsername)) {
                    continue;
                }

                String userScopedTopicDestination = "/topic/user." + safeUsername + ".group-updates";
                deliveredCount++;

                // Local delivery on current instance.
                // Check if the user is online on this instance (current instance that runs this code)
                if (rabbitMQBrokerHandler.hasLocalSubscribers(userScopedTopicDestination)) {
                    messagingTemplate.convertAndSend(userScopedTopicDestination, updateToPublish);
                }

                // Cross-instance delivery via RabbitMQ.
                rabbitMQBrokerHandler.publishToRabbitMQ(userScopedTopicDestination, updateToPublish);
            }
            logger.debug(
                    "[flushGroupMembers] Flushed buffered group summary update to {}/{} subscribed users in groupId={}, message={}",
                    deliveredCount,
                    usernames.size(),
                    groupId,
                    updateToPublish);
        } catch (Exception e) {
            logger.error("Failed to flush buffered group summary update: groupId={}", groupId, e);
        } finally {
            synchronized (pendingUpdate) {
                boolean hasUnpublishedUpdate = pendingUpdate.latestUpdate != null
                        && !Objects.equals(pendingUpdate.latestUpdate, updateToPublish);
                if (hasUnpublishedUpdate) {
                    scheduleFlushIfAbsent(groupId, pendingUpdate);
                } else if (pendingUpdate.scheduledFlush == null) {
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

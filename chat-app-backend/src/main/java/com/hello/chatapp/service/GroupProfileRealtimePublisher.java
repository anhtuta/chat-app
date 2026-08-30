package com.hello.chatapp.service;

import com.hello.chatapp.constant.MessageType;
import com.hello.chatapp.dto.GroupSummaryUpdate;
import com.hello.chatapp.dto.MessageResponse;
import com.hello.chatapp.entity.Group;
import com.hello.chatapp.entity.Message;
import com.hello.chatapp.util.AfterCommit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * Publishes group-profile realtime updates after the surrounding DB transaction commits.
 * Delivers the structured chat line and sidebar/profile metadata refresh for current members.
 */
@Service
public class GroupProfileRealtimePublisher {

    private static final Logger logger = LoggerFactory.getLogger(GroupProfileRealtimePublisher.class);

    private final RealtimeMessageDeliveryService realtimeMessageDeliveryService;
    private final GroupSummaryUpdatePublisher groupSummaryUpdatePublisher;

    public GroupProfileRealtimePublisher(
            RealtimeMessageDeliveryService realtimeMessageDeliveryService,
            GroupSummaryUpdatePublisher groupSummaryUpdatePublisher) {
        this.realtimeMessageDeliveryService = realtimeMessageDeliveryService;
        this.groupSummaryUpdatePublisher = groupSummaryUpdatePublisher;
    }

    /**
     * After commit: publish the profile-change system chat line plus refreshed group metadata.
     */
    public void publishGroupProfileChange(Group group, Message systemMessage, String latestPreview) {
        Group safeGroup = Objects.requireNonNull(group, "group must not be null");
        Message safeMessage = Objects.requireNonNull(systemMessage, "systemMessage must not be null");
        String safePreview = Objects.requireNonNull(latestPreview, "latestPreview must not be null");
        Long groupId = Objects.requireNonNull(safeGroup.getId(), "group.id must not be null");
        if (!Objects.equals(safeMessage.getMessageType(), MessageType.SYSTEM)) {
            throw new IllegalArgumentException("systemMessage must be a SYSTEM message");
        }

        AfterCommit.run(() -> {
            MessageResponse response = Objects.requireNonNull(MessageResponse.fromMessage(safeMessage));
            realtimeMessageDeliveryService.publishToGroup(groupId, response);
            logger.debug("[publishGroupProfileChange] Published profile system message to group topic, groupId={}, messageId={}",
                    groupId, safeMessage.getId());

            GroupSummaryUpdate groupProfileUpdate = GroupSummaryUpdate.forGroupProfileEvent(
                    groupId,
                    safeGroup.getName(),
                    safeGroup.getDescription(),
                    safePreview,
                    safeMessage.getTimestamp());
            groupSummaryUpdatePublisher.publishToGroupMembers(groupId, groupProfileUpdate);
        }, "Failed to publish group profile realtime update after commit");
    }
}

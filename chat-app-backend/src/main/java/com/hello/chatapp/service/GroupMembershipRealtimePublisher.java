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
 * Publishes membership-driven realtime updates after the surrounding DB transaction commits.
 * <p>
 * Delivers:
 * <ul>
 * <li>structured {@code SYSTEM} {@link MessageResponse} to {@code /topic/group.{groupId}}</li>
 * <li>sidebar {@link GroupSummaryUpdate} fan-out to remaining members</li>
 * <li>optional immediate {@code removed} personal update for a kicked/banned/leaving user</li>
 * </ul>
 */
@Service
public class GroupMembershipRealtimePublisher {

    private static final Logger logger = LoggerFactory.getLogger(GroupMembershipRealtimePublisher.class);

    private final RealtimeMessageDeliveryService realtimeMessageDeliveryService;
    private final GroupSummaryUpdatePublisher groupSummaryUpdatePublisher;

    public GroupMembershipRealtimePublisher(
            RealtimeMessageDeliveryService realtimeMessageDeliveryService,
            GroupSummaryUpdatePublisher groupSummaryUpdatePublisher) {
        this.realtimeMessageDeliveryService = realtimeMessageDeliveryService;
        this.groupSummaryUpdatePublisher = groupSummaryUpdatePublisher;
    }

    /**
     * After commit: publish the system chat line and a sidebar summary for remaining members.
     *
     * @param removedUsername when non-null, also sends {@link GroupSummaryUpdate#removed(Long)}
     *        to that user so their sidebar drops the group
     */
    public void publishMembershipChange(Group group, Message systemMessage, String latestPreview, String removedUsername) {
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
            logger.debug("[publishMembershipChange] Published membership system message to group topic, groupId={}, messageId={}",
                    groupId, safeMessage.getId());

            // Send to all group members: used by FE to update sidebar latest preview ("Member removed", etc.)
            GroupSummaryUpdate membershipChangeUpdate = GroupSummaryUpdate.forSystemEvent(
                    groupId, safeGroup.getName(), safePreview, safeMessage.getTimestamp());
            groupSummaryUpdatePublisher.publishToGroupMembers(groupId, membershipChangeUpdate);

            // Send to the removed user: used by FE to drop that group from their sidebar.
            // After kick/ban/leave, the target is already gone from group_participants, so the member fan-out never
            // includes them. Without publishToUser(removed), their sidebar would keep the group until refresh.
            if (removedUsername != null && !removedUsername.isBlank()) {
                GroupSummaryUpdate removedUpdate = GroupSummaryUpdate.removed(groupId);
                groupSummaryUpdatePublisher.publishToUser(removedUsername, removedUpdate);
                logger.debug("[publishToUser] Delivered personal group summary update to user={}, message={}",
                        removedUsername, removedUpdate);
            }
        }, "Failed to publish membership realtime update after commit");
    }
}

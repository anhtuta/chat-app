package com.hello.chatapp.dto;

import com.hello.chatapp.constant.GroupPermission;
import com.hello.chatapp.constant.GroupRole;
import com.hello.chatapp.constant.GroupSummaryUpdateAction;
import com.hello.chatapp.entity.Message;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Pushed over WebSocket to every group member whenever the group's latest-message
 * summary changes. The frontend uses this to refresh the sidebar without polling.
 *
 * Topic: /topic/user.{username}.group-updates
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class GroupSummaryUpdate {
    private Long groupId;
    @Builder.Default
    private GroupSummaryUpdateAction action = GroupSummaryUpdateAction.UPSERT;
    private String name;
    private String description;
    private String latestMessage;
    private String latestMessageSender;
    private LocalDateTime latestMessageAt;
    private GroupRole currentUserRole;
    private List<GroupPermission> currentUserPermissions;

    public static GroupSummaryUpdate fromMessage(Long groupId, Message message, String latestMessagePreview) {
        return GroupSummaryUpdate.builder()
                .groupId(groupId)
                .latestMessage(latestMessagePreview)
                .latestMessageSender(message.getUser().getUsername())
                .latestMessageAt(message.getTimestamp())
                .build();
    }

    public static GroupSummaryUpdate upsert(
            Long groupId,
            String name,
            String description,
            String latestMessage,
            String latestMessageSender,
            LocalDateTime latestMessageAt,
            GroupRole currentUserRole,
            List<GroupPermission> currentUserPermissions) {
        return GroupSummaryUpdate.builder()
                .groupId(groupId)
                .action(GroupSummaryUpdateAction.UPSERT)
                .name(name)
                .description(description)
                .latestMessage(latestMessage)
                .latestMessageSender(latestMessageSender)
                .latestMessageAt(latestMessageAt)
                .currentUserRole(currentUserRole)
                .currentUserPermissions(currentUserPermissions)
                .build();
    }

    public static GroupSummaryUpdate removed(Long groupId) {
        return GroupSummaryUpdate.builder()
                .groupId(groupId)
                .action(GroupSummaryUpdateAction.REMOVE)
                .build();
    }
}

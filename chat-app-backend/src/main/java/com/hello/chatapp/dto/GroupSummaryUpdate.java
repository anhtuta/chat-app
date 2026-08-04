package com.hello.chatapp.dto;

import com.hello.chatapp.constant.GroupPermission;
import com.hello.chatapp.constant.GroupRole;
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
    private String name;
    private String description;
    private String latestMessage;
    private String latestMessageSender;
    private LocalDateTime latestMessageAt;
    private Long unreadCount;
    private GroupRole currentUserRole;
    private List<GroupPermission> currentUserPermissions;
    private boolean removed;

    public static GroupSummaryUpdate fromMessage(Long groupId, Message message, String latestMessagePreview) {
        return GroupSummaryUpdate.builder()
                .groupId(groupId)
                .latestMessage(latestMessagePreview)
                .latestMessageSender(message.getUser().getUsername())
                .latestMessageAt(message.getTimestamp())
                .build();
    }

    public static GroupSummaryUpdate fromGroupResponse(GroupResponse groupResponse) {
        if (groupResponse == null) {
            return null;
        }
        return GroupSummaryUpdate.builder()
                .groupId(groupResponse.getId())
                .name(groupResponse.getName())
                .description(groupResponse.getDescription())
                .latestMessage(groupResponse.getLatestMessage())
                .latestMessageSender(groupResponse.getLatestMessageSender())
                .latestMessageAt(groupResponse.getLatestMessageAt())
                .unreadCount(groupResponse.getUnreadCount())
                .currentUserRole(groupResponse.getCurrentUserRole())
                .currentUserPermissions(groupResponse.getCurrentUserPermissions())
                .removed(false)
                .build();
    }

    public static GroupSummaryUpdate removed(Long groupId) {
        return GroupSummaryUpdate.builder()
                .groupId(groupId)
                .removed(true)
                .build();
    }
}

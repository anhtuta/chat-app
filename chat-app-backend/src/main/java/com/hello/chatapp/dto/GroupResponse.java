package com.hello.chatapp.dto;

import com.hello.chatapp.constant.GroupPermission;
import com.hello.chatapp.constant.GroupRole;
import com.hello.chatapp.entity.Group;
import com.hello.chatapp.entity.GroupParticipant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GroupResponse {
    private Long id;
    private String name;
    private String description;
    private Long createdById;
    private String createdByUsername;
    private LocalDateTime createdAt;
    private String latestMessage;
    private String latestMessageSender;
    private LocalDateTime latestMessageAt;
    private long unreadCount;
    private GroupRole currentUserRole;
    private List<GroupPermission> currentUserPermissions;

    public static GroupResponse fromGroup(Group group) {
        return fromGroup(group, null, List.of(), 0);
    }

    public static GroupResponse fromGroup(Group group, long unreadCount) {
        return fromGroup(group, null, List.of(), unreadCount);
    }

    public static GroupResponse fromGroup(
            Group group,
            GroupRole currentUserRole,
            List<GroupPermission> currentUserPermissions,
            long unreadCount) {
        if (group == null) {
            return null;
        }
        return GroupResponse.builder()
                .id(group.getId())
                .name(group.getName())
                .description(group.getDescription())
                .createdById(group.getCreatedBy() == null ? null : group.getCreatedBy().getId())
                .createdByUsername(group.getCreatedBy() == null ? null : group.getCreatedBy().getUsername())
                .createdAt(group.getCreatedAt())
                .latestMessage(group.getLatestMessage())
                .latestMessageSender(group.getLatestMessageSender())
                .latestMessageAt(group.getLatestMessageAt())
                .unreadCount(unreadCount)
                .currentUserRole(currentUserRole)
                .currentUserPermissions(currentUserPermissions == null ? List.of() : List.copyOf(currentUserPermissions))
                .build();
    }

    public static GroupResponse fromParticipant(
            GroupParticipant participant,
            List<GroupPermission> currentUserPermissions,
            long unreadCount) {
        if (participant == null) {
            return null;
        }
        GroupRole currentUserRole = participant.getRole() == null ? GroupRole.MEMBER : participant.getRole();
        return fromGroup(participant.getGroup(), currentUserRole, currentUserPermissions, unreadCount);
    }
}


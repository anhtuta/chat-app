package com.hello.chatapp.dto;

import com.hello.chatapp.constant.GroupPermission;
import com.hello.chatapp.constant.GroupRole;
import com.hello.chatapp.entity.Message;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import org.springframework.lang.NonNull;

/**
 * Pushed over WebSocket to every group member whenever the group's latest-message
 * summary changes. The frontend uses this to refresh the sidebar without polling.
 *
 * <p>
 * Topic: {@code /topic/user.{username}.group-updates}
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
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

    /**
     * Builds a lightweight sidebar update from a newly saved chat message (typically text/media).
     * Sets latest-message fields only; does not include group name/description or role data.
     *
     * @param groupId group whose sidebar row should refresh
     * @param message persisted message used for sender username and timestamp
     * @param latestMessagePreview truncated/human preview already computed for the sidebar
     * @return update with {@code removed=false} (Lombok boolean default) and no name/role fields
     */
    public static GroupSummaryUpdate fromMessage(Long groupId, Message message, String latestMessagePreview) {
        return GroupSummaryUpdate.builder()
                .groupId(groupId)
                .latestMessage(latestMessagePreview)
                .latestMessageSender(message.getUser().getUsername())
                .latestMessageAt(message.getTimestamp())
                .build();
    }

    /**
     * Builds a sidebar update for a structured membership/profile {@code SYSTEM} event.
     * Includes {@code name} so clients that do not yet have the group (e.g. after join/add)
     * can insert a sidebar row. Uses sender {@code "System"} to match
     * {@link com.hello.chatapp.service.MessageService#saveGroupSystemMessage} latest-message
     * summary behavior ({@link com.hello.chatapp.constant.SystemEventType#latestPreview()}).
     *
     * @param groupId group whose sidebar row should refresh
     * @param groupName current group display name
     * @param latestMessagePreview human preview such as {@code "Member joined"}
     * @param latestMessageAt timestamp of the system message
     * @return update with {@code removed=false}
     */
    public static GroupSummaryUpdate forSystemEvent(
            Long groupId,
            String groupName,
            String latestMessagePreview,
            LocalDateTime latestMessageAt) {
        return GroupSummaryUpdate.builder()
                .groupId(groupId)
                .name(groupName)
                .latestMessage(latestMessagePreview)
                .latestMessageSender("System")
                .latestMessageAt(latestMessageAt)
                .removed(false)
                .build();
    }

    /**
     * Same as {@link #forSystemEvent(Long, String, String, LocalDateTime)} but also carries the
     * recipient's refreshed role and permissions.
     *
     * @param currentUserRole effective role for the recipient after the change
     * @param currentUserPermissions effective permission set for the recipient after the change
     * @return update with {@code removed=false}
     */
    public static GroupSummaryUpdate forSystemEventWithAccess(
            Long groupId,
            String groupName,
            String latestMessagePreview,
            LocalDateTime latestMessageAt,
            GroupRole currentUserRole,
            List<GroupPermission> currentUserPermissions) {
        return GroupSummaryUpdate.builder()
                .groupId(groupId)
                .name(groupName)
                .latestMessage(latestMessagePreview)
                .latestMessageSender("System")
                .latestMessageAt(latestMessageAt)
                .currentUserRole(currentUserRole)
                .currentUserPermissions(currentUserPermissions == null ? List.of() : List.copyOf(currentUserPermissions))
                .removed(false)
                .build();
    }

    /**
     * Builds a profile-aware sidebar update for a structured group metadata event.
     * Includes current name and description so open detail/header views can refresh live.
     *
     * @return update with {@code removed=false}
     */
    public static GroupSummaryUpdate forGroupProfileEvent(
            Long groupId,
            String groupName,
            String groupDescription,
            String latestMessagePreview,
            LocalDateTime latestMessageAt) {
        return GroupSummaryUpdate.builder()
                .groupId(groupId)
                .name(groupName)
                .description(groupDescription)
                .latestMessage(latestMessagePreview)
                .latestMessageSender("System")
                .latestMessageAt(latestMessageAt)
                .removed(false)
                .build();
    }

    /**
     * Copies sidebar-relevant fields from a full {@link GroupResponse}
     * (name, description, latest message, unread, role, permissions).
     *
     * @param groupResponse source group DTO; may be {@code null}
     * @return populated update with {@code removed=false}, or {@code null} if {@code groupResponse} is null
     */
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

    /**
     * Builds a personal removal signal for one user (kick, ban of a participant, or leave).
     * Clients should drop the group from the sidebar when {@code removed} is {@code true}.
     * Does not include latest-message or profile fields.
     *
     * @param groupId group the recipient should no longer see in their list
     * @return non-null update with only {@code groupId} and {@code removed=true}
     */
    public static @NonNull GroupSummaryUpdate removed(Long groupId) {
        return Objects.requireNonNull(
                GroupSummaryUpdate.builder()
                        .groupId(groupId)
                        .removed(true)
                        .build(),
                "GroupSummaryUpdate must not be null");
    }

    /**
     * Compact debug string; omits role/permission lists to keep logs readable.
     */
    @Override
    public String toString() {
        return "GroupSummaryUpdate{" +
                "groupId=" + groupId +
                ", name='" + name + '\'' +
                ", description='" + description + '\'' +
                ", latestMessage='" + latestMessage + '\'' +
                ", latestMessageSender='" + latestMessageSender + '\'' +
                ", latestMessageAt=" + latestMessageAt +
                ", unreadCount=" + unreadCount +
                ", removed=" + removed +
                '}';
    }
}

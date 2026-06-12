package com.hello.chatapp.dto;

import com.hello.chatapp.entity.Message;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import java.time.LocalDateTime;

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
    private String latestMessage;
    private String latestMessageSender;
    private LocalDateTime latestMessageAt;

    public static GroupSummaryUpdate fromMessage(Long groupId, Message message, String latestMessagePreview) {
        return GroupSummaryUpdate.builder()
                .groupId(groupId)
                .latestMessage(latestMessagePreview)
                .latestMessageSender(message.getUser().getUsername())
                .latestMessageAt(message.getTimestamp())
                .build();
    }
}

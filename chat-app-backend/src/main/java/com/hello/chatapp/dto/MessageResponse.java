package com.hello.chatapp.dto;

import com.hello.chatapp.constant.MessageType;
import com.hello.chatapp.constant.SystemEventType;
import com.hello.chatapp.entity.Message;
import com.hello.chatapp.entity.MessageMedia;
import com.hello.chatapp.model.SystemEventPayload;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

/**
 * API representation of a chat message, including optional {@link SystemEventPayload}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MessageResponse {
    private Long id;
    private UserResponse user;
    private Long groupId;
    private MessageType messageType;
    private String content;
    private SystemEventType systemEventType;
    private UserResponse systemEventActor;
    private SystemEventPayload systemEventPayload;
    private UserResponse updatedBy;
    private LocalDateTime updatedAt;
    private UserResponse deletedBy;
    private LocalDateTime deletedAt;
    private String freshnessKey;
    private List<MessageAttachmentResponse> attachments;
    private LocalDateTime timestamp;

    /**
     * Maps a persisted message for APIs that do not go through {@link MessageResponseMapper}
     * (no storage-backed attachment URLs).
     */
    public static MessageResponse fromMessage(Message message) {
        if (message == null) {
            return null;
        }
        return MessageResponse.builder()
                .id(message.getId())
                .user(message.getUser() != null ? UserResponse.fromUser(message.getUser()) : null)
                .groupId(message.getGroup() != null ? message.getGroup().getId() : null)
                .messageType(message.getMessageType())
                .content(resolveContent(message))
                .systemEventType(resolveSystemEventType(message))
                .systemEventActor(message.getUpdatedBy() != null ? UserResponse.fromUser(message.getUpdatedBy()) : null)
                .systemEventPayload(message.getSystemEventPayload())
                .updatedBy(message.getUpdatedBy() != null ? UserResponse.fromUser(message.getUpdatedBy()) : null)
                .updatedAt(message.getUpdatedAt())
                .deletedBy(message.getDeletedBy() != null ? UserResponse.fromUser(message.getDeletedBy()) : null)
                .deletedAt(message.getDeletedAt())
                .freshnessKey(resolveFreshnessKey(message))
                .attachments(resolveAttachments(message))
                .timestamp(message.getTimestamp())
                .build();
    }

    /**
     * Returns a server-generated revision key that advances for message edits, deletes,
     * and attachment lifecycle changes.
     */
    public static String resolveFreshnessKey(Message message) {
        LocalDateTime latestChange = resolveLatestMessageOrAttachmentChange(message);
        return latestChange != null ? latestChange.toString() : null;
    }

    private static String resolveContent(Message message) {
        if (message == null || message.getDeletedAt() != null) {
            return null;
        }
        return message.getContent();
    }

    private static List<MessageAttachmentResponse> resolveAttachments(Message message) {
        if (message == null || message.getDeletedAt() != null) {
            return Collections.emptyList();
        }
        return message.getAttachments() == null
                ? Collections.emptyList()
                : message.getAttachments().stream().map(MessageAttachmentResponse::fromEntity).toList();
    }

    /**
     * Returns the latest meaningful revision timestamp across the message row and its attachments.
     */
    private static LocalDateTime resolveLatestMessageOrAttachmentChange(Message message) {
        if (message == null) {
            return null;
        }

        LocalDateTime latestChange = message.getTimestamp();
        latestChange = max(latestChange, message.getUpdatedAt());
        latestChange = max(latestChange, message.getDeletedAt());

        if (message.getAttachments() == null) {
            return latestChange;
        }

        for (MessageMedia attachment : message.getAttachments()) {
            if (attachment == null) {
                continue;
            }
            latestChange = max(latestChange, attachment.getUpdatedAt());
        }

        return latestChange;
    }

    /**
     * Returns the later non-null timestamp, or the non-null value when only one exists.
     */
    private static LocalDateTime max(LocalDateTime left, LocalDateTime right) {
        if (left == null) {
            return right;
        }
        if (right == null || !right.isAfter(left)) {
            return left;
        }
        return right;
    }

    private static SystemEventType resolveSystemEventType(Message message) {
        if (message == null || message.getMessageType() != MessageType.SYSTEM || message.getContent() == null) {
            return null;
        }
        try {
            return SystemEventType.valueOf(message.getContent());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}

